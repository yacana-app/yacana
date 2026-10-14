// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

// Reads a sky cache folder with N.I.N.A.'s own code: CacheSkySurvey opens its
// CacheInfo.xml and loads each photo, as its framing assistant does, and CacheSkySurveyImageFactory
// draws its offline sky map around the first photo into a PNG. A line per photo, then the map's file.

using System;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Runtime.Loader;
using System.Windows.Media.Imaging;
using NINA.Astrometry;
using NINA.WPF.Base.SkySurvey;

static class CacheReference {
    /** args: the N.I.N.A. folder, where its assemblies are loaded from; the cache folder; the PNG to draw. */
    [STAThread]
    public static int Main(string[] args) {
        if (args.Length != 3) {
            Console.Error.WriteLine("usage: CacheReference <NINA folder> <cache folder> <map.png>");
            return 2;
        }
        AssemblyLoadContext.Default.Resolving += (context, name) => {
            var path = Path.Combine(args[0], name.Name + ".dll");
            return File.Exists(path) ? context.LoadFromAssemblyPath(path) : null;
        };
        return Run(args[1], args[2]);
    }

    // Apart from Main, so that N.I.N.A.'s types load once its folder is known.
    static int Run(string folder, string map) {
        var cache = new CacheSkySurvey(folder);
        var images = cache.Cache.Elements("Image").ToList();
        foreach (var element in images) {
            var image = cache.GetImage(Guid.Parse(element.Attribute("Id").Value)).Result;
            Console.WriteLine(string.Format(CultureInfo.InvariantCulture,
                "{0}  RA {1:F5} h  Dec {2:F5}°  {3}′ × {4}′  {5} × {6} px  {7}",
                image.Name, image.Coordinates.RA, image.Coordinates.Dec, image.FoVWidth, image.FoVHeight,
                image.Image.PixelWidth, image.Image.PixelHeight, element.Attribute("Source").Value));
        }
        if (images.Count == 0) {
            Console.Error.WriteLine("No photos in " + folder);
            return 1;
        }
        var first = images[0];
        var center = new Coordinates(
            double.Parse(first.Attribute("RA").Value, CultureInfo.InvariantCulture),
            double.Parse(first.Attribute("Dec").Value, CultureInfo.InvariantCulture),
            Epoch.J2000, Coordinates.RAType.Hours);
        var fov = double.Parse(first.Attribute("FoVH").Value, CultureInfo.InvariantCulture) / 60;
        var drawn = new CacheSkySurveyImageFactory(1000, 1000, cache).Render(center, fov * 1.2, 0);
        using (var file = File.Create(map)) {
            var encoder = new PngBitmapEncoder();
            encoder.Frames.Add(BitmapFrame.Create(drawn));
            encoder.Save(file);
        }
        Console.WriteLine("map " + map);
        return 0;
    }
}
