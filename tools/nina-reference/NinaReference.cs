// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

// Prints the statistics and the star detection that N.I.N.A. itself gives for FITS frames, to
// check the engine against. It loads the NINA installed on this PC and takes the path its
// Imaging tab takes with the default image settings: debayer, unlinked stretch with factor 0.2 and
// black clipping -2.8, then star detection with sensitivity High and no noise reduction, HFR on
// the debayered luminance. See run.sh.

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.Loader;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

public static class NinaReference {
    static string ninaFolder, hocusFolder;

    [STAThread]
    public static int Main(string[] args) {
        if (args.Length < 2) {
            Console.Error.WriteLine("usage: NinaReference <NINA folder> <frame.fits or folder>... [--stars <folder>] [--image <folder>] [--hocus <plugin folder>]");
            return 2;
        }
        ninaFolder = args[0];
        var hocus = Array.IndexOf(args, "--hocus");
        if (hocus > 0) {
            hocusFolder = args[hocus + 1];
            // Hocus Focus loads OpenCV's native library from its own folder.
            Environment.SetEnvironmentVariable("PATH", Path.Combine(hocusFolder, "dll", "x64") + ";" + Environment.GetEnvironmentVariable("PATH"));
        }
        AssemblyLoadContext.Default.Resolving += (context, name) => {
            foreach (var folder in new[] { ninaFolder, hocusFolder }) {
                var path = folder == null ? null : Path.Combine(folder, name.Name + ".dll");
                if (path != null && File.Exists(path)) return context.LoadFromAssemblyPath(path);
            }
            return null;
        };
        return Run(args.Skip(1).ToArray());
    }

    // Kept apart from Main so that NINA's assemblies load only after the resolver is in place.
    static int Run(string[] args) {
        // NINA logs to the console: send that to stderr, and keep stdout for the JSON.
        var json = Console.Out;
        Console.SetOut(Console.Error);
        string starsFolder = null, imageFolder = null;
        var frames = new List<string>();
        for (var i = 0; i < args.Length; i++) {
            if (args[i] == "--stars") {
                starsFolder = args[++i];
            } else if (args[i] == "--image") {
                imageFolder = args[++i];
            } else if (args[i] == "--hocus") {
                i++;
            } else if (Directory.Exists(args[i])) {
                // A folder stands for its FITS frames, so a long night fits on the command line.
                frames.AddRange(Directory.GetFiles(args[i]).Where(f => f.EndsWith(".fit") || f.EndsWith(".fits")).OrderBy(f => f, StringComparer.Ordinal));
            } else {
                frames.Add(args[i]);
            }
        }
        var output = new StringBuilder("[\n");
        foreach (var frame in frames) {
            var line = Analyse(frame, starsFolder, imageFolder).GetAwaiter().GetResult();
            output.Append(line).Append(frame == frames[frames.Count - 1] ? "\n" : ",\n");
        }
        json.Write(output.Append("]\n"));
        json.Flush();
        return 0;
    }

    static async Task<string> Analyse(string path, string starsFolder, string imageFolder) {
        var fits = Fits.Read(path);
        var meta = new NINA.Image.ImageData.ImageMetaData();
        meta.Camera.PixelSize = fits.PixelSize;
        meta.Telescope.FocalLength = fits.FocalLength;
        var pattern = (NINA.Core.Enum.SensorType)Enum.Parse(typeof(NINA.Core.Enum.SensorType), fits.Bayer ?? "RGGB");
        meta.Camera.SensorType = pattern;

        var detection = new NINA.Image.ImageAnalysis.StarDetection();
        var data = new NINA.Image.ImageData.BaseImageData(fits.Pixels, fits.Width, fits.Height, 16, fits.Bayer != null, meta, null, detection, null);
        var statistics = NINA.Image.ImageData.ImageStatistics.Create(data);

        NINA.Image.Interfaces.IRenderedImage rendered = data.RenderImage();
        if (fits.Bayer != null) {
            rendered = rendered.Debayer(saveColorChannels: true, saveLumChannel: true, bayerPattern: pattern);
        }
        rendered = await rendered.Stretch(0.2, -2.8, unlinked: fits.Bayer != null);
        var parameters = new NINA.Image.ImageAnalysis.StarDetectionParams {
            IsAutoFocus = false,
            Sensitivity = NINA.Core.Enum.StarSensitivityEnum.High,
            NoiseReduction = NINA.Core.Enum.NoiseReductionEnum.None,
        };
        var result = await detection.Detect(rendered, rendered.Image.Format, parameters, null, CancellationToken.None);

        if (imageFolder != null) {
            // The frame as NINA shows it, debayered and stretched, as an 8-bit PPM (or PGM if mono).
            Directory.CreateDirectory(imageFolder);
            var shown = rendered.Image;
            int width = shown.PixelWidth, height = shown.PixelHeight, channels = shown.Format.BitsPerPixel / 16;
            var pixels = new ushort[width * height * channels];
            shown.CopyPixels(pixels, width * channels * 2, 0);
            var bytes = new byte[pixels.Length];
            for (var i = 0; i < pixels.Length; i++) bytes[i] = (byte)(pixels[i] >> 8);
            var name = Path.GetFileNameWithoutExtension(path) + (channels == 3 ? ".ppm" : ".pgm");
            using var file = File.Create(Path.Combine(imageFolder, name));
            file.Write(Encoding.ASCII.GetBytes((channels == 3 ? "P6" : "P5") + "\n" + width + " " + height + "\n255\n"));
            file.Write(bytes);
        }

        if (starsFolder != null) {
            Directory.CreateDirectory(starsFolder);
            var stars = new StringBuilder("x,y,hfr,background,max,boxX,boxY,boxWidth,boxHeight\n");
            foreach (var s in result.StarList) {
                stars.AppendLine(string.Join(",", F(s.Position.X), F(s.Position.Y), F(s.HFR), F(s.Background), F(s.MaxBrightness), s.BoundingBox.X, s.BoundingBox.Y, s.BoundingBox.Width, s.BoundingBox.Height));
            }
            File.WriteAllText(Path.Combine(starsFolder, Path.GetFileNameWithoutExtension(path) + ".csv"), stars.ToString());
        }

        var hocusFields = "";
#if HOCUS
        if (hocusFolder != null) hocusFields = await HocusFocus(data, fits, pattern, path, starsFolder);
#endif
        return "  {" + string.Join(", ",
            Q("file") + ": " + Q(Path.GetFileName(path)),
            Q("mean") + ": " + F(statistics.Mean),
            Q("median") + ": " + F(statistics.Median),
            Q("stdDev") + ": " + F(statistics.StDev),
            Q("mad") + ": " + F(statistics.MedianAbsoluteDeviation),
            Q("min") + ": " + statistics.Min,
            Q("max") + ": " + statistics.Max,
            Q("stars") + ": " + result.DetectedStars,
            Q("hfr") + ": " + F(result.AverageHFR),
            Q("hfrStdDev") + ": " + F(result.HFRStdDev)) + hocusFields + "}";
    }

#if HOCUS
    // The Hocus Focus plugin's star detection with its default settings, and its PSF fit (an elliptical
    // Moffat with beta 4): the frame's FWHM is the median of the stars it fits with R2 of 0.9 or more.
    static async Task<string> HocusFocus(NINA.Image.ImageData.BaseImageData data, Fits fits, NINA.Core.Enum.SensorType pattern, string path, string starsFolder) {
        NINA.Image.Interfaces.IRenderedImage rendered = data.RenderImage();
        if (fits.Bayer != null) rendered = rendered.Debayer(saveColorChannels: false, saveLumChannel: false, bayerPattern: pattern);
        var build = typeof(NINA.Joko.Plugins.HocusFocus.StarDetection.HocusFocusStarDetection).GetMethod("BuildDefaultStarDetectorParams", BindingFlags.NonPublic | BindingFlags.Static);
        var p = (NINA.Joko.Plugins.HocusFocus.Interfaces.StarDetectorParams)build.Invoke(null, null);
        // NINA's image scale from the frame: AstroUtil.ArcsecPerPixel.
        p.PixelScale = fits.PixelSize / fits.FocalLength * 206.26480624709636;
        // HOCUS_INTERMEDIATE names a folder for the plugin's intermediate images, to see what it measures on.
        var intermediate = Environment.GetEnvironmentVariable("HOCUS_INTERMEDIATE");
        if (!string.IsNullOrEmpty(intermediate)) p.SaveIntermediateFilesPath = intermediate;
        var detector = new NINA.Joko.Plugins.HocusFocus.StarDetection.StarDetector(new NINA.Joko.Plugins.HocusFocus.Utility.AlglibAPI());
        var result = await detector.Detect(rendered, p, null, CancellationToken.None);
        var fitted = result.DetectedStars.Where(s => s.PSF != null).ToList();
        var (fwhm, fwhmMad) = NINA.Joko.Plugins.HocusFocus.Utility.MathUtility.MedianMAD(fitted.Select(s => s.PSF.FWHMArcsecs));
        var (fwhmPixels, _) = NINA.Joko.Plugins.HocusFocus.Utility.MathUtility.MedianMAD(fitted.Select(s => s.PSF.FWHMPixels));
        if (starsFolder != null) {
            var stars = new StringBuilder("x,y,boxX,boxY,boxWidth,boxHeight,background,hfr,fwhmPixels,fwhmX,fwhmY,rSquared\n");
            foreach (var s in result.DetectedStars) {
                var psf = s.PSF;
                stars.AppendLine(string.Join(",", F(s.Center.X), F(s.Center.Y), s.StarBoundingBox.X, s.StarBoundingBox.Y, s.StarBoundingBox.Width, s.StarBoundingBox.Height,
                    F(s.Background), F(s.HFR), psf == null ? "" : F(psf.FWHMPixels), psf == null ? "" : F(psf.FWHMx), psf == null ? "" : F(psf.FWHMy), psf == null ? "" : F(psf.RSquared)));
            }
            File.WriteAllText(Path.Combine(starsFolder, Path.GetFileNameWithoutExtension(path) + ".hocus.csv"), stars.ToString());
        }
        return ", " + string.Join(", ",
            Q("hocusStars") + ": " + result.DetectedStars.Count,
            Q("hocusFitted") + ": " + fitted.Count,
            Q("hocusFwhm") + ": " + F(fwhm),
            Q("hocusFwhmMad") + ": " + F(fwhmMad),
            Q("hocusFwhmPixels") + ": " + F(fwhmPixels));
    }
#endif

    static string F(double value) => value.ToString("R", CultureInfo.InvariantCulture);

    static string Q(string text) => "\"" + text.Replace("\\", "\\\\").Replace("\"", "\\\"") + "\"";

    /// <summary>A 16-bit FITS image as stored, rows in file order, with the keywords NINA needs.</summary>
    sealed class Fits {
        public int Width, Height;
        public ushort[] Pixels;
        public string Bayer;
        public double PixelSize = double.NaN, FocalLength = double.NaN;

        public static Fits Read(string path) {
            var bytes = File.ReadAllBytes(path);
            var cards = new Dictionary<string, string>();
            var offset = 0;
            var end = false;
            while (!end) {
                for (var i = 0; i < 36; i++) {
                    var card = Encoding.ASCII.GetString(bytes, offset + i * 80, 80);
                    var key = card.Substring(0, 8).Trim();
                    if (key == "END") { end = true; break; }
                    if (card.Length > 10 && card[8] == '=') {
                        var value = card.Substring(10).Split('/')[0].Trim().Trim('\'').Trim();
                        cards[key] = value;
                    }
                }
                offset += 2880;
            }
            if (cards["BITPIX"] != "16") throw new InvalidDataException("Only 16-bit FITS: " + path);
            var fits = new Fits {
                Width = int.Parse(cards["NAXIS1"]),
                Height = int.Parse(cards["NAXIS2"]),
                Bayer = cards.TryGetValue("BAYERPAT", out var bayer) ? bayer : null,
            };
            if (cards.TryGetValue("XPIXSZ", out var pixel)) fits.PixelSize = double.Parse(pixel, CultureInfo.InvariantCulture);
            if (cards.TryGetValue("FOCALLEN", out var focal)) fits.FocalLength = double.Parse(focal, CultureInfo.InvariantCulture);
            var zero = cards.TryGetValue("BZERO", out var z) ? double.Parse(z, CultureInfo.InvariantCulture) : 0;
            fits.Pixels = new ushort[fits.Width * fits.Height];
            for (var i = 0; i < fits.Pixels.Length; i++) {
                var raw = (short)((bytes[offset + 2 * i] << 8) | bytes[offset + 2 * i + 1]);
                fits.Pixels[i] = (ushort)(raw + zero);
            }
            return fits;
        }
    }
}
