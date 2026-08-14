using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.WebUtilities;
using Microsoft.Net.Http.Headers;

using WorkCadenceTransfer.Receiver;

ReceiverOptions options;
try
{
    options = ReceiverOptions.Parse(args);
}
catch (ArgumentException exception)
{
    Console.Error.WriteLine(exception.Message);
    Environment.ExitCode = 2;
    return;
}

var state = new ReceiverState(options);
if (options.CreateEnrollment)
{
    // This is an explicit one-time operator action. The output is the QR JSON payload;
    // it is not written to a log or persisted by the receiver.
    Console.WriteLine(Encoding.UTF8.GetString(ContractJson.Serialize(state.CreateEnrollment())));
    return;
}

var builder = WebApplication.CreateBuilder(args);
builder.WebHost.ConfigureKestrel(serverOptions =>
{
    serverOptions.Limits.MaxRequestBodySize = 26_500_000;
    serverOptions.Limits.RequestHeadersTimeout = TimeSpan.FromSeconds(30);
    serverOptions.Listen(options.ListenAddress, options.Port, listenOptions => listenOptions.UseHttps(state.Certificate));
});

var app = builder.Build();

app.MapPost("/v1/enrollments/{enrollmentId}/complete", async (HttpContext context, string enrollmentId) =>
{
    if (!IsJson(context.Request.ContentType))
    {
        await WriteError(context.Response, "INVALID_SUBMISSION", false, 400);
        return;
    }
    if (!HeaderAuth.TryGet(context.Request.Headers.Authorization.ToString(), "WCTEnrollment", out var enrollmentSecret))
    {
        await WriteError(context.Response, "ENROLLMENT_REJECTED", false, 400);
        return;
    }

    try
    {
        var body = await ReadAtMost(context.Request.Body, 16_384, context.RequestAborted);
        var request = ContractJson.DeserializeStrict<EnrollmentRequest>(body);
        if (request.Version != 1 || request.Type != "transfer_enrollment_request" || request.ClientPlatform is not ("android" or "ios"))
        {
            await WriteError(context.Response, "INVALID_SUBMISSION", false, 400);
            return;
        }
        if (!state.Enrollment.TryConsume(enrollmentId, enrollmentSecret))
        {
            await WriteError(context.Response, "ENROLLMENT_REJECTED", false, 400);
            return;
        }

        var deviceId = Guid.NewGuid().ToString("D");
        var deviceSecret = Base64Url.Encode(System.Security.Cryptography.RandomNumberGenerator.GetBytes(32));
        state.Devices.Add(deviceId, deviceSecret);
        var result = new EnrollmentResult(1, "transfer_enrollment_result", deviceId, deviceSecret, "transfer_upload", ReceiverState.UtcNow());
        await WriteJson(context.Response, result, 200);
    }
    catch (JsonException)
    {
        await WriteError(context.Response, "INVALID_SUBMISSION", false, 400);
    }
    catch (InvalidDataException)
    {
        await WriteError(context.Response, "RESOURCE_LIMIT_EXCEEDED", false, 413);
    }
});

app.MapPost("/v1/submissions/{submissionId}", async (HttpContext context, string submissionId) =>
{
    if (!HeaderAuth.TryGet(context.Request.Headers.Authorization.ToString(), "WCTDevice", out var credential))
    {
        await WriteError(context.Response, "DEVICE_UNAUTHORIZED", false, 401);
        return;
    }
    var separator = credential.IndexOf('.');
    if (separator <= 0 || separator == credential.Length - 1)
    {
        await WriteError(context.Response, "DEVICE_UNAUTHORIZED", false, 401);
        return;
    }
    var deviceId = credential[..separator];
    var deviceSecret = credential[(separator + 1)..];
    if (!state.Devices.Authenticate(deviceId, deviceSecret, out var scope) || scope != "transfer_upload")
    {
        await WriteError(context.Response, "DEVICE_UNAUTHORIZED", false, 401);
        return;
    }
    if (context.Request.ContentLength is > 26_500_000)
    {
        await WriteError(context.Response, "RESOURCE_LIMIT_EXCEEDED", false, 413);
        return;
    }

    var boundary = GetBoundary(context.Request.ContentType);
    if (boundary is null)
    {
        await WriteError(context.Response, "INVALID_SUBMISSION", false, 400);
        return;
    }

    try
    {
        var reader = new MultipartReader(boundary, context.Request.Body)
        {
            HeadersLengthLimit = 8192,
            HeadersCountLimit = 20,
            BodyLengthLimit = 26_500_000
        };
        var metadataSection = await reader.ReadNextSectionAsync(context.RequestAborted);
        if (metadataSection is null || PartName(metadataSection) != "metadata")
        {
            await WriteError(context.Response, "INVALID_SUBMISSION", false, 400);
            return;
        }
        var section = metadataSection;
        if (section.Headers is null || !section.Headers.TryGetValue("Content-Type", out var metadataContentType) ||
            !IsJson(metadataContentType.ToString()))
        {
            await WriteError(context.Response, "INVALID_SUBMISSION", false, 400);
            return;
        }

        var metadata = ContractJson.DeserializeStrict<SubmissionMetadata>(
            await ReadAtMost(section.Body, 16_384, context.RequestAborted));
        var validation = SubmissionContract.Validate(metadata, submissionId, deviceId);
        if (!validation.Valid)
        {
            var code = validation.ErrorCode ?? "INVALID_SUBMISSION";
            await WriteError(context.Response, code, code is "STORAGE_BUSY" or "SUBMISSION_IN_PROGRESS", ErrorStatus(code));
            return;
        }

        if (await reader.ReadNextSectionAsync(context.RequestAborted) is not null)
        {
            await WriteError(context.Response, "INVALID_SUBMISSION", false, 400);
            return;
        }

        await state.Index.Gate.WaitAsync(context.RequestAborted);
        try
        {
            var existing = state.Index.Find(deviceId, submissionId);
            if (existing is not null)
            {
                if (!string.Equals(existing.ContentDigest, validation.ContentDigest, StringComparison.Ordinal))
                {
                    await WriteError(context.Response, "SUBMISSION_CONFLICT", false, 409);
                    return;
                }
                await WriteJson(context.Response, ToAck(existing), 200);
                return;
            }

            var ready = state.WriteMemoReady(
                metadata,
                validation,
                Guid.NewGuid().ToString("D"),
                Guid.NewGuid().ToString("D"));
            state.Index.Add(ready);
            await WriteJson(context.Response, ToAck(ready), 200);
        }
        finally
        {
            state.Index.Gate.Release();
        }
    }
    catch (JsonException)
    {
        await WriteError(context.Response, "INVALID_SUBMISSION", false, 400);
    }
    catch (InvalidDataException)
    {
        await WriteError(context.Response, "RESOURCE_LIMIT_EXCEEDED", false, 413);
    }
    catch (OperationCanceledException) when (context.RequestAborted.IsCancellationRequested)
    {
        // The client left foreground or disconnected. No READY/ACK is created here.
    }
});

Console.WriteLine($"WorkCadenceTransfer receiver listening on https://{options.ListenAddress}:{options.Port}");
await app.RunAsync();

static ReadyAck ToAck(ReadyRecord record) => new(
    1,
    "transfer_ready_ack",
    true,
    "READY",
    record.DeviceId,
    record.SubmissionId,
    record.ContentDigest,
    record.RecordId,
    record.StoredAt);

static int ErrorStatus(string code) => code switch
{
    "DEVICE_UNAUTHORIZED" => 401,
    "SUBMISSION_CONFLICT" => 409,
    "RESOURCE_LIMIT_EXCEEDED" => 413,
    "STORAGE_BUSY" => 503,
    _ => 400
};

static bool IsJson(string? contentType) =>
    contentType is not null &&
    string.Equals(contentType.Split(';', 2)[0].Trim(), "application/json", StringComparison.OrdinalIgnoreCase);

static string? GetBoundary(string? contentType)
{
    if (contentType is null || !contentType.StartsWith("multipart/form-data", StringComparison.OrdinalIgnoreCase))
    {
        return null;
    }
    foreach (var parameter in contentType.Split(';').Skip(1))
    {
        var parts = parameter.Split('=', 2);
        if (parts.Length == 2 && string.Equals(parts[0].Trim(), "boundary", StringComparison.OrdinalIgnoreCase))
        {
            var value = parts[1].Trim().Trim('"');
            return value.Length is >= 16 and <= 70 && value.All(character => char.IsLetterOrDigit(character) || character is '_' or '-')
                ? value
                : null;
        }
    }
    return null;
}

static string? PartName(MultipartSection section)
{
    if (!ContentDispositionHeaderValue.TryParse(section.ContentDisposition, out var disposition))
    {
        return null;
    }
    return HeaderUtilities.RemoveQuotes(disposition.Name).Value;
}

static async Task<byte[]> ReadAtMost(Stream source, int limit, CancellationToken cancellationToken)
{
    using var memory = new MemoryStream();
    var buffer = new byte[8192];
    while (true)
    {
        var read = await source.ReadAsync(buffer, cancellationToken);
        if (read == 0)
        {
            return memory.ToArray();
        }
        if (memory.Length + read > limit)
        {
            throw new InvalidDataException("bounded body limit exceeded");
        }
        memory.Write(buffer, 0, read);
    }
}

static async Task WriteJson(HttpResponse response, object value, int statusCode)
{
    response.StatusCode = statusCode;
    response.ContentType = "application/json";
    await response.Body.WriteAsync(ContractJson.Serialize(value));
}

static Task WriteError(HttpResponse response, string code, bool retryable, int statusCode) =>
    WriteJson(response, new TransferError(1, "transfer_error", false, code, retryable), statusCode);
