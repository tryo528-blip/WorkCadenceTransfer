using System.Net;
using System.Globalization;
using System.Formats.Asn1;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;

namespace WorkCadenceTransfer.Receiver;

public sealed record ReceiverOptions(IPAddress ListenAddress, int Port, string DataRoot, bool CreateEnrollment)
{
    public static ReceiverOptions Parse(string[] args)
    {
        string? listen = null;
        string? root = null;
        var createEnrollment = false;
        for (var index = 0; index < args.Length; index++)
        {
            switch (args[index])
            {
                case "--listen" when index + 1 < args.Length:
                    listen = args[++index];
                    break;
                case "--root" when index + 1 < args.Length:
                    root = args[++index];
                    break;
                case "--create-enrollment":
                    createEnrollment = true;
                    break;
                default:
                    throw new ArgumentException("Usage: --listen <RFC1918-IPv4:port> [--root <C:\\subfolder>] [--create-enrollment]");
            }
        }

        if (listen is null)
        {
            throw new ArgumentException("--listen is required. Example: --listen 192.168.0.10:8443");
        }
        var separator = listen.LastIndexOf(':');
        if (separator <= 0 || separator == listen.Length - 1 ||
            !IPAddress.TryParse(listen[..separator], out var address) ||
            !int.TryParse(listen[(separator + 1)..], out var port) ||
            address.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork ||
            port is < 1024 or > 65535 || !IsRfc1918(address))
        {
            throw new ArgumentException("--listen must be an RFC1918 IPv4 address with a port from 1024 through 65535");
        }

        return new ReceiverOptions(address, port, DataPath.Validate(root ?? @"C:\WorkCadenceTransferData"), createEnrollment);
    }

    private static bool IsRfc1918(IPAddress address)
    {
        var bytes = address.GetAddressBytes();
        return bytes[0] == 10 ||
               (bytes[0] == 172 && bytes[1] is >= 16 and <= 31) ||
               (bytes[0] == 192 && bytes[1] == 168);
    }
}

public sealed class ReceiverState
{
    public ReceiverState(ReceiverOptions options)
    {
        Options = options;
        Directory.CreateDirectory(options.DataRoot);
        Certificate = CertificateStore.LoadOrCreate(Path.Combine(options.DataRoot, "receiver-cert.dpapi"));
        SpkiSha256 = Base64Url.Encode(SHA256.HashData(Spki.Export(Certificate)));
        Enrollment = new EnrollmentStore(Path.Combine(options.DataRoot, "enrollment.dpapi"));
        Devices = new DeviceRegistry(Path.Combine(options.DataRoot, "devices.dpapi"));
        Index = new ReadyIndex(Path.Combine(options.DataRoot, "idempotency-index.dpapi"));
        StagingRoot = Path.Combine(options.DataRoot, ".staging");
        ReadyRoot = Path.Combine(options.DataRoot, "ready");
        StagingFiles.Reconcile(StagingRoot);
        Index.Reconcile(ReadyRoot);
    }

    public ReceiverOptions Options { get; }
    public X509Certificate2 Certificate { get; }
    public string SpkiSha256 { get; }
    public EnrollmentStore Enrollment { get; }
    public DeviceRegistry Devices { get; }
    public ReadyIndex Index { get; }
    public SemaphoreSlim PhotoGate { get; } = new(1, 1);
    public string StagingRoot { get; }
    public string ReadyRoot { get; }

    public PairingQr CreateEnrollment()
    {
        var endpoint = $"https://{Options.ListenAddress}:{Options.Port}";
        return Enrollment.Create(endpoint, SpkiSha256);
    }

    public ReadyRecord WriteMemoReady(
        SubmissionMetadata metadata,
        SubmissionValidation validation,
        IReadOnlyList<NormalizedPhoto> normalizedPhotos,
        string uploadId,
        string recordId)
    {
        Directory.CreateDirectory(StagingRoot);
        Directory.CreateDirectory(ReadyRoot);
        var stagingPath = Path.Combine(StagingRoot, uploadId);
        var readyPath = Path.Combine(ReadyRoot, recordId);
        var recordKey = RandomNumberGenerator.GetBytes(32);
        try
        {
            Directory.CreateDirectory(stagingPath);
            AtomicFile.Write(Path.Combine(stagingPath, "key.dpapi"),
                ProtectedData.Protect(recordKey, null, DataProtectionScope.CurrentUser));

            var normalized = metadata with { Memo = validation.NormalizedMemo };
            var manifestAad = Aad.Build(recordId, metadata.DeviceId, metadata.SubmissionId, "manifest", "none", validation.ContentDigest);
            var memoAad = Aad.Build(recordId, metadata.DeviceId, metadata.SubmissionId, "memo", "none", validation.ContentDigest);
            var storedPhotos = normalizedPhotos
                .Select((photo, index) => new StoredPhotoMetadata(
                    photo.PhotoId,
                    photo.EncodedBytes.LongLength,
                    photo.Sha256,
                    photo.Width,
                    photo.Height,
                    photo.OrientationApplied,
                    $"photo-{index + 1:D2}.enc"))
                .ToList();
            AtomicFile.Write(
                Path.Combine(stagingPath, "manifest.enc"),
                Encrypt(ContractJson.Serialize(new ReadyManifest(normalized, storedPhotos)), recordKey, manifestAad));
            AtomicFile.Write(Path.Combine(stagingPath, "memo.enc"), Encrypt(Encoding.UTF8.GetBytes(validation.NormalizedMemo), recordKey, memoAad));
            foreach (var (photo, stored) in normalizedPhotos.Zip(storedPhotos))
            {
                var photoAad = Aad.Build(recordId, metadata.DeviceId, metadata.SubmissionId, "photo", photo.PhotoId, validation.ContentDigest);
                AtomicFile.Write(Path.Combine(stagingPath, stored.BlobName), Encrypt(photo.EncodedBytes, recordKey, photoAad));
            }

            Directory.Move(stagingPath, readyPath);
            return new ReadyRecord(
                metadata.DeviceId,
                metadata.SubmissionId,
                validation.ContentDigest,
                recordId,
                UtcNow());
        }
        catch
        {
            StagingFiles.DeleteKeyFirst(stagingPath);
            throw;
        }
        finally
        {
            foreach (var photo in normalizedPhotos)
            {
                CryptographicOperations.ZeroMemory(photo.EncodedBytes);
            }
            CryptographicOperations.ZeroMemory(recordKey);
        }
    }

    private static byte[] Encrypt(byte[] plaintext, byte[] key, byte[] aad)
    {
        var nonce = RandomNumberGenerator.GetBytes(12);
        var ciphertext = new byte[plaintext.Length];
        var tag = new byte[16];
        using var aes = new AesGcm(key, 16);
        aes.Encrypt(nonce, plaintext, ciphertext, tag, aad);
        var result = new byte[nonce.Length + tag.Length + ciphertext.Length];
        Buffer.BlockCopy(nonce, 0, result, 0, nonce.Length);
        Buffer.BlockCopy(tag, 0, result, nonce.Length, tag.Length);
        Buffer.BlockCopy(ciphertext, 0, result, nonce.Length + tag.Length, ciphertext.Length);
        return result;
    }

    public static string UtcNow() => DateTimeOffset.UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'");
}

public sealed record EnrollmentRecord(string EnrollmentId, string SecretVerifier, string ExpiresAt, bool Used);

public sealed class EnrollmentStore
{
    private readonly string _path;
    private EnrollmentRecord? _record;

    public EnrollmentStore(string path)
    {
        _path = path;
        _record = ProtectedJson.Load<EnrollmentRecord>(path);
    }

    public PairingQr Create(string endpoint, string spkiSha256)
    {
        var secret = RandomNumberGenerator.GetBytes(32);
        var record = new EnrollmentRecord(
            Guid.NewGuid().ToString("D"),
            HashBytes(secret),
            DateTimeOffset.UtcNow.AddMinutes(10).ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'"),
            false);
        _record = record;
        ProtectedJson.Save(_path, record);
        return new PairingQr(1, "transfer_pairing_qr", endpoint, spkiSha256, record.EnrollmentId, Base64Url.Encode(secret), record.ExpiresAt);
    }

    public bool TryConsume(string enrollmentId, string secret)
    {
        var record = _record;
        if (record is null || record.Used || record.EnrollmentId != enrollmentId ||
            !DateTimeOffset.TryParseExact(record.ExpiresAt, "yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal, out var expiresAt) ||
            expiresAt <= DateTimeOffset.UtcNow)
        {
            return false;
        }
        var decoded = Base64Url.Decode(secret);
        if (decoded is not { Length: 32 })
        {
            return false;
        }
        byte[] expected;
        try
        {
            expected = Convert.FromHexString(record.SecretVerifier);
        }
        catch (FormatException)
        {
            return false;
        }
        var actual = SHA256.HashData(decoded);
        if (!CryptographicOperations.FixedTimeEquals(expected, actual))
        {
            return false;
        }
        _record = record with { Used = true };
        ProtectedJson.Save(_path, _record);
        return true;
    }

    private static string HashBytes(byte[] bytes) => Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
}

public sealed record DeviceCredential(string DeviceId, string SecretVerifier, string Scope, bool Revoked);

public sealed class DeviceRegistry
{
    private readonly string _path;
    private readonly List<DeviceCredential> _records;

    public DeviceRegistry(string path)
    {
        _path = path;
        _records = ProtectedJson.Load<List<DeviceCredential>>(path) ?? [];
    }

    public void Add(string deviceId, string secret)
    {
        _records.Add(new DeviceCredential(deviceId, HashSecret(secret), "transfer_upload", false));
        ProtectedJson.Save(_path, _records);
    }

    public bool Authenticate(string deviceId, string secret, out string? scope)
    {
        scope = null;
        var credential = _records.FirstOrDefault(item => item.DeviceId == deviceId);
        if (credential is null || credential.Revoked || credential.Scope != "transfer_upload")
        {
            return false;
        }
        var expected = Convert.FromHexString(credential.SecretVerifier);
        var actual = Convert.FromHexString(HashSecret(secret));
        var valid = CryptographicOperations.FixedTimeEquals(expected, actual);
        if (valid)
        {
            scope = credential.Scope;
        }
        return valid;
    }

    private static string HashSecret(string secret) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(secret))).ToLowerInvariant();
}

public sealed record ReadyRecord(string DeviceId, string SubmissionId, string ContentDigest, string RecordId, string StoredAt);

public sealed class ReadyIndex
{
    private readonly string _path;
    private readonly List<ReadyRecord> _records;

    public ReadyIndex(string path)
    {
        _path = path;
        _records = ProtectedJson.Load<List<ReadyRecord>>(path) ?? [];
    }

    public SemaphoreSlim Gate { get; } = new(1, 1);

    public ReadyRecord? Find(string deviceId, string submissionId) =>
        _records.FirstOrDefault(item => item.DeviceId == deviceId && item.SubmissionId == submissionId);

    public void Add(ReadyRecord record)
    {
        _records.Add(record);
        ProtectedJson.Save(_path, _records);
    }

    public void Reconcile(string readyRoot)
    {
        Directory.CreateDirectory(readyRoot);
        var indexed = _records.Select(record => record.RecordId).ToHashSet(StringComparer.Ordinal);
        var changed = _records.RemoveAll(record => !Directory.Exists(Path.Combine(readyRoot, record.RecordId))) > 0;
        foreach (var directory in Directory.EnumerateDirectories(readyRoot))
        {
            if (!indexed.Contains(Path.GetFileName(directory)))
            {
                StagingFiles.DeleteKeyFirst(directory);
            }
        }
        if (changed)
        {
            ProtectedJson.Save(_path, _records);
        }
    }
}

public static class HeaderAuth
{
    public static bool TryGet(string? header, string scheme, out string value)
    {
        value = string.Empty;
        if (header is null || !header.StartsWith(scheme + " ", StringComparison.Ordinal))
        {
            return false;
        }
        value = header[(scheme.Length + 1)..];
        return value.Length > 0;
    }
}

public static class Base64Url
{
    public static string Encode(byte[] bytes) => Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    public static byte[]? Decode(string value)
    {
        if (value.Length == 0 || value.Any(character => !(char.IsLetterOrDigit(character) || character is '-' or '_')))
        {
            return null;
        }
        var padded = value.Replace('-', '+').Replace('_', '/');
        padded += new string('=', (4 - padded.Length % 4) % 4);
        try
        {
            return Convert.FromBase64String(padded);
        }
        catch (FormatException)
        {
            return null;
        }
    }
}

public static class Aad
{
    public static byte[] Build(string recordId, string deviceId, string submissionId, string blobKind, string photoId, string contentDigest)
    {
        using var stream = new MemoryStream();
        stream.Write("WCTENC1"u8);
        WriteLengthPrefixed(stream, "windows-ready");
        WriteLengthPrefixed(stream, recordId);
        WriteLengthPrefixed(stream, deviceId);
        WriteLengthPrefixed(stream, submissionId);
        WriteLengthPrefixed(stream, blobKind);
        WriteLengthPrefixed(stream, photoId);
        stream.Write(Convert.FromHexString(contentDigest));
        return stream.ToArray();
    }

    private static void WriteLengthPrefixed(Stream stream, string value)
    {
        var bytes = Encoding.UTF8.GetBytes(value);
        Span<byte> length = stackalloc byte[4];
        System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(length, checked((uint)bytes.Length));
        stream.Write(length);
        stream.Write(bytes);
    }
}

public static class ProtectedJson
{
    public static T? Load<T>(string path)
    {
        if (!File.Exists(path))
        {
            return default;
        }
        var protectedBytes = File.ReadAllBytes(path);
        var plain = ProtectedData.Unprotect(protectedBytes, null, DataProtectionScope.CurrentUser);
        return ContractJson.DeserializeStrict<T>(plain);
    }

    public static void Save<T>(string path, T value)
    {
        var plain = ContractJson.Serialize(value);
        var protectedBytes = ProtectedData.Protect(plain, null, DataProtectionScope.CurrentUser);
        AtomicFile.Write(path, protectedBytes);
    }
}

public static class CertificateStore
{
    public static X509Certificate2 LoadOrCreate(string path)
    {
        if (File.Exists(path))
        {
            var protectedBytes = File.ReadAllBytes(path);
            var loadedPfx = ProtectedData.Unprotect(protectedBytes, null, DataProtectionScope.CurrentUser);
            return LoadForWindowsTls(loadedPfx);
        }

        using var rsa = RSA.Create(2048);
        var request = new CertificateRequest("CN=WorkCadenceTransfer Receiver", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, false));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, false));
        var usages = new OidCollection { new("1.3.6.1.5.5.7.3.1") };
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(usages, false));
        using var created = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-5), DateTimeOffset.UtcNow.AddYears(10));
        var pfx = created.Export(X509ContentType.Pkcs12, string.Empty);
        AtomicFile.Write(path, ProtectedData.Protect(pfx, null, DataProtectionScope.CurrentUser));
        return LoadForWindowsTls(pfx);
    }

    private static X509Certificate2 LoadForWindowsTls(byte[] pfx) =>
        new(pfx, string.Empty, X509KeyStorageFlags.UserKeySet | X509KeyStorageFlags.PersistKeySet | X509KeyStorageFlags.Exportable);
}

public static class Spki
{
    public static byte[] Export(X509Certificate2 certificate)
    {
        var writer = new AsnWriter(AsnEncodingRules.DER);
        writer.PushSequence();
        writer.PushSequence();
        writer.WriteObjectIdentifier(certificate.PublicKey.Oid.Value!);
        writer.WriteEncodedValue(certificate.PublicKey.EncodedParameters.RawData);
        writer.PopSequence();
        writer.WriteEncodedValue(certificate.PublicKey.EncodedKeyValue.RawData);
        writer.PopSequence();
        return writer.Encode();
    }
}

public static class AtomicFile
{
    public static void Write(string path, byte[] bytes)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var partial = path + "." + Guid.NewGuid().ToString("N") + ".partial";
        try
        {
            using (var stream = new FileStream(partial, FileMode.CreateNew, FileAccess.Write, FileShare.None, 4096, FileOptions.WriteThrough))
            {
                stream.Write(bytes);
                stream.Flush(true);
            }
            File.Move(partial, path, true);
        }
        finally
        {
            if (File.Exists(partial))
            {
                File.Delete(partial);
            }
        }
    }
}

public static class StagingFiles
{
    public static void Reconcile(string root)
    {
        if (!Directory.Exists(root))
        {
            return;
        }
        foreach (var directory in Directory.EnumerateDirectories(root))
        {
            DeleteKeyFirst(directory);
        }
        foreach (var file in Directory.EnumerateFiles(root))
        {
            File.Delete(file);
        }
    }

    public static void DeleteKeyFirst(string directory)
    {
        var key = Path.Combine(directory, "key.dpapi");
        if (File.Exists(key))
        {
            File.Delete(key);
        }
        if (Directory.Exists(directory))
        {
            Directory.Delete(directory, true);
        }
    }
}

public static class DataPath
{
    public static string Validate(string value)
    {
        var full = Path.GetFullPath(value);
        if (full.StartsWith(@"\\", StringComparison.Ordinal) ||
            !full.StartsWith(@"C:\", StringComparison.OrdinalIgnoreCase) ||
            string.Equals(full, @"C:\", StringComparison.OrdinalIgnoreCase))
        {
            throw new ArgumentException("data root must be a non-root local C: path; UNC and device paths are rejected");
        }

        var existing = new DirectoryInfo(full);
        while (!existing.Exists && existing.Parent is not null)
        {
            existing = existing.Parent;
        }
        if ((existing.Attributes & FileAttributes.ReparsePoint) != 0)
        {
            throw new ArgumentException("data root or its existing parent is a reparse point");
        }
        return full;
    }
}
