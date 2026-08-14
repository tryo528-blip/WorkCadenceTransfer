using System.Buffers.Binary;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace WorkCadenceTransfer.Receiver;

public sealed record PhotoMetadata(string PhotoId, string Mime, long Bytes, string Sha256);

public sealed record SubmissionMetadata(
    int Version,
    string Type,
    string DeviceId,
    string SubmissionId,
    string CreatedAt,
    string TargetDate,
    string ContentDigest,
    string Memo,
    List<PhotoMetadata>? Photos);

public sealed record EnrollmentRequest(int Version, string Type, string ClientPlatform);

public sealed record PairingQr(
    int Version,
    string Type,
    string Endpoint,
    string SpkiSha256,
    string EnrollmentId,
    string EnrollmentSecret,
    string ExpiresAt);

public sealed record EnrollmentResult(
    int Version,
    string Type,
    string DeviceId,
    string DeviceSecret,
    string Scope,
    string IssuedAt);

public sealed record ReadyAck(
    int Version,
    string Type,
    bool Accepted,
    string State,
    string DeviceId,
    string SubmissionId,
    string ContentDigest,
    string RecordId,
    string StoredAt);

public sealed record TransferError(int Version, string Type, bool Accepted, string Code, bool Retryable);

public sealed record SubmissionValidation(
    bool Valid,
    string? ErrorCode,
    string? ErrorMessage,
    string NormalizedMemo,
    string ContentDigest);

public static class ContractJson
{
    public static readonly JsonSerializerOptions Options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = false,
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
        WriteIndented = false
    };

    public static T DeserializeStrict<T>(ReadOnlySpan<byte> bytes)
    {
        using var document = JsonDocument.Parse(bytes.ToArray(), new JsonDocumentOptions
        {
            AllowTrailingCommas = false,
            CommentHandling = JsonCommentHandling.Disallow
        });
        RejectDuplicateKeys(document.RootElement);
        return JsonSerializer.Deserialize<T>(bytes, Options)
            ?? throw new JsonException("JSON value is null");
    }

    public static byte[] Serialize<T>(T value) => JsonSerializer.SerializeToUtf8Bytes(value, Options);

    private static void RejectDuplicateKeys(JsonElement element)
    {
        if (element.ValueKind == JsonValueKind.Object)
        {
            var names = new HashSet<string>(StringComparer.Ordinal);
            foreach (var property in element.EnumerateObject())
            {
                if (!names.Add(property.Name))
                {
                    throw new JsonException($"duplicate JSON key: {property.Name}");
                }
                RejectDuplicateKeys(property.Value);
            }
        }
        else if (element.ValueKind == JsonValueKind.Array)
        {
            foreach (var child in element.EnumerateArray())
            {
                RejectDuplicateKeys(child);
            }
        }
    }
}

public static class SubmissionContract
{
    private static readonly Regex UuidV4 = new("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$", RegexOptions.CultureInvariant);
    private static readonly Regex UtcMilliseconds = new("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$", RegexOptions.CultureInvariant);
    private static readonly Regex LocalDate = new("^[0-9]{4}-[0-9]{2}-[0-9]{2}$", RegexOptions.CultureInvariant);
    private static readonly Regex Sha256 = new("^[0-9a-f]{64}$", RegexOptions.CultureInvariant);
    private static readonly HashSet<int> ContractWhitespace =
    [
        ..Enumerable.Range(0x0009, 0x0006),
        0x0020, 0x0085, 0x00A0, 0x1680,
        ..Enumerable.Range(0x2000, 0x000B),
        0x2028, 0x2029, 0x202F, 0x205F, 0x3000
    ];

    public static SubmissionValidation Validate(SubmissionMetadata submission, string pathSubmissionId, string authenticatedDeviceId)
    {
        if (submission.Version != 1 || submission.Type != "transfer_submission")
        {
            return Reject("INVALID_SUBMISSION", "version/type is not transfer_submission v1");
        }
        if (!IsUuidV4(submission.DeviceId) || !IsUuidV4(submission.SubmissionId))
        {
            return Reject("INVALID_SUBMISSION", "deviceId and submissionId must be lowercase canonical UUID v4");
        }
        if (submission.SubmissionId != pathSubmissionId)
        {
            return Reject("INVALID_SUBMISSION", "path and metadata submission IDs differ");
        }
        if (submission.DeviceId != authenticatedDeviceId)
        {
            return Reject("DEVICE_UNAUTHORIZED", "body device ID differs from authenticated principal");
        }
        if (!IsUtcMilliseconds(submission.CreatedAt) || !IsLocalDate(submission.TargetDate))
        {
            return Reject("INVALID_SUBMISSION", "createdAt or targetDate is not a valid canonical date/time");
        }

        if (submission.Memo is null)
        {
            return Reject("INVALID_SUBMISSION", "memo is required");
        }
        var normalizedMemo = NormalizeMemo(submission.Memo);
        if (Encoding.UTF8.GetByteCount(normalizedMemo) > 8192 || ContractWhitespace.ContainsAny(normalizedMemo))
        {
            return Reject("INVALID_SUBMISSION", "memo is empty or exceeds 8,192 UTF-8 bytes");
        }
        if (submission.Photos is null || submission.Photos.Count > 5)
        {
            return Reject("INVALID_SUBMISSION", "photos must contain zero to five items");
        }

        var photoIds = new HashSet<string>(StringComparer.Ordinal);
        long totalBytes = 0;
        foreach (var photo in submission.Photos)
        {
            if (photo is null)
            {
                return Reject("INVALID_SUBMISSION", "photo entries must not be null");
            }
            if (!IsUuidV4(photo.PhotoId) || !photoIds.Add(photo.PhotoId))
            {
                return Reject("INVALID_SUBMISSION", "photo IDs must be unique lowercase canonical UUID v4");
            }
            if (photo.Mime != "image/jpeg" || photo.Bytes is < 1 or > 5_242_880 || !Sha256.IsMatch(photo.Sha256))
            {
                return Reject("INVALID_SUBMISSION", "photo metadata is outside the v1 limits");
            }
            totalBytes = checked(totalBytes + photo.Bytes);
        }
        if (totalBytes > 26_214_400)
        {
            return Reject("RESOURCE_LIMIT_EXCEEDED", "total photo bytes exceed 25 MiB");
        }
        if (submission.Photos.Count > 0)
        {
            return Reject("INVALID_MEDIA", "photo normalization is not enabled in the memo-only receiver");
        }
        if (!Sha256.IsMatch(submission.ContentDigest))
        {
            return Reject("INVALID_SUBMISSION", "contentDigest must be lowercase SHA-256");
        }

        var digest = CanonicalDigest(submission, normalizedMemo);
        if (!CryptographicOperations.FixedTimeEquals(
                Convert.FromHexString(submission.ContentDigest),
                Convert.FromHexString(digest)))
        {
            return Reject("CONTENT_DIGEST_MISMATCH", "contentDigest differs from canonical submission bytes");
        }
        return new SubmissionValidation(true, null, null, normalizedMemo, digest);
    }

    public static string NormalizeMemo(string memo) =>
        memo.Replace("\r\n", "\n", StringComparison.Ordinal)
            .Replace('\r', '\n')
            .Normalize(NormalizationForm.FormC);

    public static string CanonicalDigest(SubmissionMetadata submission, string normalizedMemo)
    {
        using var stream = new MemoryStream();
        stream.Write("WCT1"u8);
        stream.WriteByte(1);
        WriteLengthPrefixed(stream, submission.DeviceId);
        WriteLengthPrefixed(stream, submission.SubmissionId);
        WriteLengthPrefixed(stream, submission.CreatedAt);
        WriteLengthPrefixed(stream, submission.TargetDate);
        WriteLengthPrefixed(stream, normalizedMemo);
        stream.WriteByte((byte)(submission.Photos?.Count ?? 0));
        Span<byte> integer = stackalloc byte[8];
        foreach (var photo in submission.Photos ?? [])
        {
            WriteLengthPrefixed(stream, photo.PhotoId);
            WriteLengthPrefixed(stream, photo.Mime);
            BinaryPrimitives.WriteUInt64BigEndian(integer, checked((ulong)photo.Bytes));
            stream.Write(integer);
            stream.Write(Convert.FromHexString(photo.Sha256));
        }
        return Convert.ToHexString(SHA256.HashData(stream.ToArray())).ToLowerInvariant();
    }

    private static void WriteLengthPrefixed(Stream stream, string value)
    {
        var bytes = Encoding.UTF8.GetBytes(value);
        Span<byte> length = stackalloc byte[4];
        BinaryPrimitives.WriteUInt32BigEndian(length, checked((uint)bytes.Length));
        stream.Write(length);
        stream.Write(bytes);
    }

    private static bool IsUuidV4(string? value) =>
        value is not null && UuidV4.IsMatch(value) && Guid.TryParseExact(value, "D", out var parsed) && parsed.ToString("D") == value;

    private static bool IsUtcMilliseconds(string? value) =>
        value is not null && UtcMilliseconds.IsMatch(value) &&
        DateTimeOffset.TryParseExact(value, "yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal, out var parsed) &&
        parsed.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture) == value;

    private static bool IsLocalDate(string? value) =>
        value is not null && LocalDate.IsMatch(value) && DateOnly.TryParseExact(value, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var parsed) && parsed.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture) == value;

    private static SubmissionValidation Reject(string code, string message) => new(false, code, message, string.Empty, string.Empty);
}

internal static class StringSetExtensions
{
    public static bool ContainsAny(this HashSet<int> set, string value)
    {
        return value.Length == 0 || value.All(character => set.Contains(character));
    }
}
