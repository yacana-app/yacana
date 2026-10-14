#!/usr/bin/env bash
# Prints N.I.N.A.'s own statistics and star detection for FITS frames, as JSON.
#
# Windows only, with N.I.N.A. 3 installed, the .NET 8 runtimes (Microsoft.NETCore.App and
# Microsoft.WindowsDesktop.App) and a C# compiler from Visual Studio or its Build Tools. Run it
# from Git Bash:
#
#   tools/nina-reference/run.sh <frame.fits or folder>... [--stars <folder>] [--image <folder>]
#
# --stars also writes each frame's star list as CSV, and --image the frame as NINA shows it. With
# the Hocus Focus plugin installed, its FWHM comes too (hocus* fields, and .hocus.csv star lists).
# NINA_DIR, HOCUS_DIR and CSC override where N.I.N.A., Hocus Focus and the compiler are.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
nina="${NINA_DIR:-/c/Program Files/N.I.N.A. - Nighttime Imaging 'N' Astronomy}"
hocus="${HOCUS_DIR:-$LOCALAPPDATA/NINA/Plugins/3.0.0/Hocus Focus}"
csc="${CSC:-$(ls -d "/c/Program Files (x86)/Microsoft Visual Studio/"*/*/MSBuild/Current/Bin/Roslyn/csc.exe 2>/dev/null | tail -1)}"
out="$here/build"
[ -f "$nina/NINA.Image.dll" ] || { echo "N.I.N.A. not found in $nina (set NINA_DIR)" >&2; exit 1; }
[ -f "$csc" ] || { echo "No C# compiler found (set CSC to csc.exe)" >&2; exit 1; }

if [ ! -f "$out/NinaReference.dll" ] || [ "$here/NinaReference.cs" -nt "$out/NinaReference.dll" ]; then
    mkdir -p "$out"
    # Every managed assembly in N.I.N.A.'s folder, which carries its own .NET 8, as references.
    NINA_WIN="$(cygpath -w "$nina")" powershell.exe -NoProfile -Command \
        'Get-ChildItem -LiteralPath $env:NINA_WIN -Filter *.dll | ForEach-Object { try { [void][Reflection.AssemblyName]::GetAssemblyName($_.FullName); "/reference:""" + $_.FullName + """" } catch {} }' \
        | tr -d '\r' > "$out/references.rsp"
    defines=""
    if [ -f "$hocus/NINA.Joko.Plugins.HocusFocus.dll" ]; then
        # Hocus Focus is there: build its part too (--hocus), against its own assemblies.
        defines="-define:HOCUS"
        HOCUS_WIN="$(cygpath -w "$hocus")" powershell.exe -NoProfile -Command \
            'Get-ChildItem -LiteralPath $env:HOCUS_WIN -Filter *.dll | ForEach-Object { try { [void][Reflection.AssemblyName]::GetAssemblyName($_.FullName); "/reference:""" + $_.FullName + """" } catch {} }' \
            | tr -d '\r' >> "$out/references.rsp"
    fi
    "$csc" -nologo -noconfig -nostdlib -target:exe -langversion:latest -nowarn:1701,1702 $defines \
        "@$(cygpath -w "$out/references.rsp")" -out:"$(cygpath -w "$out/NinaReference.dll")" "$(cygpath -w "$here/NinaReference.cs")" >&2
    cat > "$out/NinaReference.runtimeconfig.json" <<'JSON'
{
  "runtimeOptions": {
    "tfm": "net8.0",
    "frameworks": [
      { "name": "Microsoft.NETCore.App", "version": "8.0.0" },
      { "name": "Microsoft.WindowsDesktop.App", "version": "8.0.0" }
    ]
  }
}
JSON
fi

args=()
for arg in "$@"; do
    if [ "$arg" = "--stars" ] || [ "$arg" = "--image" ]; then args+=("$arg"); else args+=("$(cygpath -w "$arg")"); fi
done
# With Hocus Focus installed, its numbers come too.
if [ -f "$hocus/NINA.Joko.Plugins.HocusFocus.dll" ]; then args+=(--hocus "$(cygpath -w "$hocus")"); fi
"${DOTNET:-/c/Program Files/dotnet/dotnet.exe}" "$(cygpath -w "$out/NinaReference.dll")" "$(cygpath -w "$nina")" "${args[@]}"
