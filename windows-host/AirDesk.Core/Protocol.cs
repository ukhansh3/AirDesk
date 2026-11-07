using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Numerics;
using System.Globalization;

namespace AirDesk.Core;

public sealed class ProtocolFailure(string code, bool sequenceAccepted = false) : Exception(code)
{
    public string Code { get; } = code;
    public bool SequenceAccepted { get; } = sequenceAccepted;
}

public sealed record WireMessage(string Type, string SessionId, long Sequence, long TimestampMs, JsonElement Payload)
{
    public static WireMessage Parse(string json, SchemaCatalog schemas)
    {
        if (Encoding.UTF8.GetByteCount(json) > 8192) throw new ProtocolFailure("INVALID_PAYLOAD");
        try
        {
            using var document = JsonDocument.Parse(json, new JsonDocumentOptions { MaxDepth = 16 });
            var value = document.RootElement;
            if (!Unique(value)) throw new ProtocolFailure("INVALID_PAYLOAD");
            if (value.TryGetProperty("protocolVersion", out var version) && version.ValueKind == JsonValueKind.String && version.GetString() != "0.1")
                throw new ProtocolFailure("INVALID_VERSION");
            if (value.ValueKind != JsonValueKind.Object || !value.TryGetProperty("type", out var typeElement) || typeElement.ValueKind != JsonValueKind.String)
                throw new ProtocolFailure("INVALID_PAYLOAD");
            var type = typeElement.GetString()!;
            var family = type.StartsWith("pairing.") ? "pairing/pairing-messages.schema.json" :
                type.StartsWith("session.") && type is not "session.locked" and not "session.closed" ? "session/session-messages.schema.json" :
                type.StartsWith("pointer.") || type.StartsWith("keyboard.") || type == "input.release_all" ? "input/input-event.schema.json" :
                type == "protocol.error" ? "errors/error.schema.json" : "control/messages.schema.json";
            if (!schemas.Accepts(family, value)) throw new ProtocolFailure("INVALID_PAYLOAD");
            return new(type, value.GetProperty("sessionId").GetString()!, JsonNumbers.Int64(value.GetProperty("sequence")),
                JsonNumbers.Int64(value.GetProperty("timestampMs")), value.GetProperty("payload").Clone());
        }
        catch (ProtocolFailure) { throw; }
        catch (Exception ex) when (ex is JsonException or InvalidOperationException or KeyNotFoundException or FormatException or OverflowException)
        { throw new ProtocolFailure("INVALID_PAYLOAD"); }
    }

    public string ToJson() => JsonSerializer.Serialize(new { protocolVersion = "0.1", type = Type, sessionId = SessionId, sequence = Sequence, timestampMs = TimestampMs, payload = Payload });
    public static WireMessage Create(string type, string session, long sequence, long timestamp, object payload) =>
        new(type, session, sequence, timestamp, JsonSerializer.SerializeToElement(payload));
    public override string ToString() => $"{Type} (payload redacted)";
    private static bool Unique(JsonElement value) => value.ValueKind switch
    {
        JsonValueKind.Object => value.EnumerateObject().Select(p => p.Name).Distinct().Count() == value.EnumerateObject().Count() && value.EnumerateObject().All(p => Unique(p.Value)),
        JsonValueKind.Array => value.EnumerateArray().All(Unique),
        _ => true
    };
}

/// <summary>Fail-closed evaluator for the exact keyword subset in canonical AirDesk schemas.</summary>
public sealed class SchemaCatalog
{
    private readonly Dictionary<string, JsonElement> _schemas = new();
    private static readonly HashSet<string> Keywords = new(["$schema", "title", "description", "type", "const", "enum", "properties", "required", "additionalProperties", "oneOf", "minimum", "maximum", "minLength", "maxLength", "pattern", "items", "minItems", "maxItems", "uniqueItems"]);
    public SchemaCatalog(string? root = null)
    {
        root ??= Path.Combine(AppContext.BaseDirectory, "contracts");
        foreach (var file in Directory.GetFiles(root, "*.schema.json", SearchOption.AllDirectories))
        {
            using var document = JsonDocument.Parse(File.ReadAllText(file));
            _schemas[Path.GetRelativePath(root, file).Replace('\\', '/')] = document.RootElement.Clone();
        }
    }
    public bool Accepts(string path, JsonElement value) => _schemas.TryGetValue(path, out var schema) && Match(schema, value);
    private static bool Match(JsonElement schema, JsonElement value)
    {
        if (schema.EnumerateObject().Any(p => !Keywords.Contains(p.Name))) return false;
        if (schema.TryGetProperty("const", out var constant) && !JsonElement.DeepEquals(constant, value)) return false;
        if (schema.TryGetProperty("enum", out var enumeration) && !enumeration.EnumerateArray().Any(e => JsonElement.DeepEquals(e, value))) return false;
        if (schema.TryGetProperty("type", out var type))
        {
            bool IsType(string? name) => name switch
            {
                "object" => value.ValueKind == JsonValueKind.Object,
                "array" => value.ValueKind == JsonValueKind.Array,
                "string" => value.ValueKind == JsonValueKind.String,
                "boolean" => value.ValueKind is JsonValueKind.True or JsonValueKind.False,
                "null" => value.ValueKind == JsonValueKind.Null,
                "integer" => JsonNumbers.TryInt64(value, out _),
                "number" => value.ValueKind == JsonValueKind.Number && value.TryGetDouble(out var n) && double.IsFinite(n),
                _ => false
            };
            if (type.ValueKind == JsonValueKind.Array ? !type.EnumerateArray().Any(e => IsType(e.GetString())) : !IsType(type.GetString())) return false;
        }
        if (value.ValueKind == JsonValueKind.String)
        {
            var text = value.GetString()!;
            if (!ValidUnicode(text)) return false;
            var count = text.EnumerateRunes().Count();
            if (schema.TryGetProperty("minLength", out var min) && count < min.GetInt32()) return false;
            if (schema.TryGetProperty("maxLength", out var max) && count > max.GetInt32()) return false;
            if (schema.TryGetProperty("pattern", out var pattern) && !Regex.IsMatch(text, pattern.GetString()!, RegexOptions.CultureInvariant, TimeSpan.FromMilliseconds(50))) return false;
        }
        if (value.ValueKind == JsonValueKind.Number)
        {
            if (!value.TryGetDouble(out var number) || !double.IsFinite(number)) return false;
            if (schema.TryGetProperty("minimum", out var min) && number < min.GetDouble()) return false;
            if (schema.TryGetProperty("maximum", out var max) && number > max.GetDouble()) return false;
        }
        if (value.ValueKind == JsonValueKind.Object)
        {
            var hasProperties = schema.TryGetProperty("properties", out var properties);
            if (schema.TryGetProperty("required", out var required) && required.EnumerateArray().Any(e => !value.TryGetProperty(e.GetString()!, out _))) return false;
            foreach (var property in value.EnumerateObject())
            {
                if (hasProperties && properties.TryGetProperty(property.Name, out var child)) { if (!Match(child, property.Value)) return false; }
                else if (schema.TryGetProperty("additionalProperties", out var additional) && additional.ValueKind == JsonValueKind.False) return false;
            }
        }
        if (value.ValueKind == JsonValueKind.Array)
        {
            var array = value.EnumerateArray().ToArray();
            if (schema.TryGetProperty("minItems", out var min) && array.Length < min.GetInt32()) return false;
            if (schema.TryGetProperty("maxItems", out var max) && array.Length > max.GetInt32()) return false;
            if (schema.TryGetProperty("uniqueItems", out var unique) && unique.GetBoolean())
                for (var i = 0; i < array.Length; i++) for (var j = i + 1; j < array.Length; j++) if (JsonElement.DeepEquals(array[i], array[j])) return false;
            if (schema.TryGetProperty("items", out var item) && array.Any(e => !Match(item, e))) return false;
        }
        return !schema.TryGetProperty("oneOf", out var variants) || variants.EnumerateArray().Count(s => Match(s, value)) == 1;
    }
    public static bool ValidUnicode(string text)
    {
        for (var i = 0; i < text.Length; i++)
            if (char.IsHighSurrogate(text[i])) { if (++i >= text.Length || !char.IsLowSurrogate(text[i])) return false; }
            else if (char.IsLowSurrogate(text[i])) return false;
        return true;
    }
}

public static class JsonNumbers
{
    public static bool TryInt64(JsonElement element, out long result)
    {
        result = 0;
        if (element.ValueKind != JsonValueKind.Number) return false;
        var raw = element.GetRawText(); var exponentAt = raw.IndexOfAny(['e', 'E']);
        var significant = exponentAt < 0 ? raw : raw[..exponentAt];
        var exponent = exponentAt < 0 ? BigInteger.Zero : BigInteger.Parse(raw[(exponentAt + 1)..], CultureInfo.InvariantCulture);
        var dot = significant.IndexOf('.'); var fractional = dot < 0 ? 0 : significant.Length - dot - 1;
        var digits = significant.Replace(".", ""); var number = BigInteger.Parse(digits, CultureInfo.InvariantCulture);
        if (number.IsZero) return true;
        var scale = exponent - fractional;
        if (scale > 19 || scale < -digits.Length) return false;
        if (scale >= 0) number *= BigInteger.Pow(10, (int)scale);
        else
        {
            number = BigInteger.DivRem(number, BigInteger.Pow(10, (int)-scale), out var remainder);
            if (!remainder.IsZero) return false;
        }
        if (number < long.MinValue || number > long.MaxValue) return false;
        result = (long)number; return true;
    }
    public static long Int64(JsonElement value) => TryInt64(value, out var result) ? result : throw new ProtocolFailure("INVALID_PAYLOAD");
    public static int Int32(JsonElement value) { var number = Int64(value); return number is >= int.MinValue and <= int.MaxValue ? (int)number : throw new ProtocolFailure("INVALID_PAYLOAD"); }
}
