using System.Buffers.Binary;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Security.Cryptography;

namespace WorkCadenceTransfer.Receiver;

public sealed record NormalizedPhoto(
    string PhotoId,
    byte[] EncodedBytes,
    string Sha256,
    int Width,
    int Height,
    int OrientationApplied);

public sealed record PhotoNormalizationResult(
    bool Valid,
    string? ErrorCode,
    string? ErrorMessage,
    NormalizedPhoto? Photo)
{
    public static PhotoNormalizationResult Invalid(string code, string message) =>
        new(false, code, message, null);
}

public static class PhotoNormalizer
{
    public const int MaxDimension = 4_096;
    public const long MaxPixels = 12_000_000;
    public const int MaxEncodedBytes = 5_242_880;
    private const long JpegQuality = 90;
    private const int ExifOrientationId = 0x0112;

    public static PhotoNormalizationResult Normalize(byte[] encoded, string photoId)
    {
        if (!JpegHeader.TryGetDimensions(encoded, out var headerWidth, out var headerHeight))
        {
            return PhotoNormalizationResult.Invalid("INVALID_MEDIA", "photo is not a valid JPEG header");
        }
        if (!WithinPixelLimits(headerWidth, headerHeight))
        {
            return PhotoNormalizationResult.Invalid("RESOURCE_LIMIT_EXCEEDED", "photo dimensions exceed the v1 limits");
        }

        try
        {
            using var input = new MemoryStream(encoded, writable: false);
            using var source = Image.FromStream(input, useEmbeddedColorManagement: false, validateImageData: true);
            if (source.RawFormat.Guid != ImageFormat.Jpeg.Guid ||
                source.Width != headerWidth || source.Height != headerHeight)
            {
                return PhotoNormalizationResult.Invalid("INVALID_MEDIA", "photo JPEG decode does not match its header");
            }

            var orientation = ReadOrientation(source);
            if (orientation is null)
            {
                return PhotoNormalizationResult.Invalid("INVALID_MEDIA", "photo EXIF orientation is malformed");
            }

            using var oriented = new Bitmap(source);
            oriented.RotateFlip(ToRotateFlipType(orientation.Value));
            if (!WithinPixelLimits(oriented.Width, oriented.Height))
            {
                return PhotoNormalizationResult.Invalid("RESOURCE_LIMIT_EXCEEDED", "oriented photo dimensions exceed the v1 limits");
            }

            using var normalized = new Bitmap(oriented.Width, oriented.Height, PixelFormat.Format24bppRgb);
            using (var graphics = Graphics.FromImage(normalized))
            {
                graphics.CompositingMode = CompositingMode.SourceCopy;
                graphics.CompositingQuality = CompositingQuality.HighQuality;
                graphics.InterpolationMode = InterpolationMode.HighQualityBicubic;
                graphics.PixelOffsetMode = PixelOffsetMode.HighQuality;
                graphics.SmoothingMode = SmoothingMode.HighQuality;
                graphics.DrawImage(
                    oriented,
                    new Rectangle(0, 0, normalized.Width, normalized.Height),
                    0,
                    0,
                    oriented.Width,
                    oriented.Height,
                    GraphicsUnit.Pixel);
            }

            using var output = new MemoryStream();
            using var encoderParameters = new EncoderParameters(1);
            using var quality = new EncoderParameter(Encoder.Quality, JpegQuality);
            encoderParameters.Param[0] = quality;
            normalized.Save(output, JpegEncoder(), encoderParameters);
            var bytes = output.ToArray();
            if (bytes.Length is < 1 or > MaxEncodedBytes)
            {
                CryptographicOperations.ZeroMemory(bytes);
                return PhotoNormalizationResult.Invalid("RESOURCE_LIMIT_EXCEEDED", "normalized photo bytes exceed the v1 limits");
            }

            return new PhotoNormalizationResult(
                true,
                null,
                null,
                new NormalizedPhoto(
                    photoId,
                    bytes,
                    Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant(),
                    normalized.Width,
                    normalized.Height,
                    orientation.Value));
        }
        catch (ArgumentException)
        {
            return PhotoNormalizationResult.Invalid("INVALID_MEDIA", "photo JPEG decode failed");
        }
        catch (ExternalException)
        {
            return PhotoNormalizationResult.Invalid("INVALID_MEDIA", "photo JPEG normalization failed");
        }
        catch (OutOfMemoryException)
        {
            return PhotoNormalizationResult.Invalid("RESOURCE_LIMIT_EXCEEDED", "photo decode memory limit was exceeded");
        }
    }

    private static bool WithinPixelLimits(int width, int height) =>
        width is > 0 and <= MaxDimension &&
        height is > 0 and <= MaxDimension &&
        (long)width * height <= MaxPixels;

    private static int? ReadOrientation(Image image)
    {
        if (!image.PropertyIdList.Contains(ExifOrientationId))
        {
            return 1;
        }

        try
        {
            var property = image.GetPropertyItem(ExifOrientationId);
            if (property is null)
            {
                return null;
            }
            var propertyValue = property.Value;
            if (property.Type != 3 || property.Len < 2 || propertyValue is null || propertyValue.Length < 2)
            {
                return null;
            }
            var orientation = BinaryPrimitives.ReadUInt16LittleEndian(propertyValue.AsSpan(0, 2));
            return orientation is >= 1 and <= 8 ? orientation : null;
        }
        catch (ArgumentException)
        {
            return null;
        }
    }

    private static RotateFlipType ToRotateFlipType(int orientation) => orientation switch
    {
        1 => RotateFlipType.RotateNoneFlipNone,
        2 => RotateFlipType.RotateNoneFlipX,
        3 => RotateFlipType.Rotate180FlipNone,
        4 => RotateFlipType.Rotate180FlipX,
        5 => RotateFlipType.Rotate90FlipX,
        6 => RotateFlipType.Rotate90FlipNone,
        7 => RotateFlipType.Rotate270FlipX,
        8 => RotateFlipType.Rotate270FlipNone,
        _ => throw new ArgumentOutOfRangeException(nameof(orientation))
    };

    private static ImageCodecInfo JpegEncoder() =>
        ImageCodecInfo.GetImageEncoders().First(codec => codec.MimeType == "image/jpeg");
}

internal static class JpegHeader
{
    public static bool TryGetDimensions(ReadOnlySpan<byte> data, out int width, out int height)
    {
        width = 0;
        height = 0;
        if (data.Length < 4 || data[0] != 0xFF || data[1] != 0xD8)
        {
            return false;
        }

        var index = 2;
        while (index < data.Length)
        {
            if (data[index++] != 0xFF)
            {
                return false;
            }
            while (index < data.Length && data[index] == 0xFF)
            {
                index++;
            }
            if (index >= data.Length)
            {
                return false;
            }

            var marker = data[index++];
            if (marker is 0xD8 or 0xD9 or 0xDA)
            {
                return false;
            }
            if (marker == 0x01 || marker is >= 0xD0 and <= 0xD7)
            {
                continue;
            }
            if (index + 2 > data.Length)
            {
                return false;
            }

            var segmentLength = BinaryPrimitives.ReadUInt16BigEndian(data.Slice(index, 2));
            if (segmentLength < 2 || index + segmentLength > data.Length)
            {
                return false;
            }
            if (IsFrameMarker(marker))
            {
                if (segmentLength < 8)
                {
                    return false;
                }
                var precision = data[index + 2];
                height = BinaryPrimitives.ReadUInt16BigEndian(data.Slice(index + 3, 2));
                width = BinaryPrimitives.ReadUInt16BigEndian(data.Slice(index + 5, 2));
                return precision == 8 && width > 0 && height > 0;
            }
            index += segmentLength;
        }
        return false;
    }

    private static bool IsFrameMarker(byte marker) => marker switch
    {
        0xC0 or 0xC1 or 0xC2 or 0xC3 or
        0xC5 or 0xC6 or 0xC7 or
        0xC9 or 0xCA or 0xCB or
        0xCD or 0xCE or 0xCF => true,
        _ => false
    };
}
