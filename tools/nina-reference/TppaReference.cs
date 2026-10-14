// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

// The Three Point Polar Alignment plugin's own error for three plate solves: each line of
// the input is a case, and for each one a line "RESULT altitude azimuth" gives its error in degrees
// (N.I.N.A. logs to the output too).
//
//   latitude longitude elevation time pressure temperature humidity wavelength refractionAdjustment
//   ra1 dec1 ra2 dec2 ra3 dec3
//
// Angles in degrees, positions in J2000, the time in ISO 8601 UTC, refractionAdjustment true or false.
// UT1 − UTC comes from a database of the program's own, which N.I.N.A.'s scripts create.

using System;
using System.Globalization;
using System.IO;
using System.Runtime.Loader;
using NINA.Astrometry;
using NINA.Core.Utility;
using NINA.PlateSolving;
using NINA.Plugins.PolarAlignment;

class FixedTime : ICustomDateTime {
    private readonly DateTime time;

    public FixedTime(DateTime time) {
        this.time = time;
    }

    public DateTime Now => time;

    public DateTime UtcNow => time;
}

static class TppaReference {
    static double D(string s) => double.Parse(s, CultureInfo.InvariantCulture);

    /** args: the N.I.N.A. folder and the plugin's folder, where their assemblies are loaded from. */
    public static void Main(string[] args) {
        AssemblyLoadContext.Default.Resolving += (context, name) => {
            foreach (var folder in args) {
                var path = Path.Combine(folder, name.Name + ".dll");
                if (File.Exists(path)) return context.LoadFromAssemblyPath(path);
            }
            return null;
        };
        Run();
    }

    // Apart from Main, so that N.I.N.A.'s types load once the folders are known.
    static void Run() {
        string line;
        while ((line = Console.ReadLine()) != null) {
            line = line.Trim();
            if (line.Length == 0 || line.StartsWith("#")) continue;
            var f = line.Split(' ', StringSplitOptions.RemoveEmptyEntries);
            var latitude = Angle.ByDegree(D(f[0]));
            var longitude = Angle.ByDegree(D(f[1]));
            var elevation = D(f[2]);
            var utc = DateTime.Parse(f[3], CultureInfo.InvariantCulture, DateTimeStyles.AdjustToUniversal | DateTimeStyles.AssumeUniversal);
            var time = new FixedTime(utc);
            var refraction = new RefractionParameters(D(f[4]), D(f[5]), D(f[6]), D(f[7]));
            var adjust = bool.Parse(f[8]);
            Coordinates At(int i) => new Coordinates(Angle.ByDegree(D(f[9 + 2 * i])), Angle.ByDegree(D(f[10 + 2 * i])), Epoch.J2000, time);
            Position P(int i) => new Position(At(i), 0, latitude, longitude, elevation, refraction);
            var error = new PolarErrorDetermination(new PlateSolveResult() { Coordinates = At(2) }, P(0), P(1), P(2), latitude, longitude, elevation, refraction, adjust);
            Console.WriteLine(string.Format(CultureInfo.InvariantCulture, "RESULT {0:R} {1:R}", error.InitialMountAxisAltitudeError.Degree, error.InitialMountAxisAzimuthError.Degree));
        }
    }
}
