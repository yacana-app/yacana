#!/usr/bin/env python3
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""An Alpaca camera that serves real frames: each exposure returns the next 16-bit FITS file of a
folder, so Yacana's imaging features (stars, HFR, plate solving) can be tried with real stars and
no sky. It takes the frames' own size, pixel size, Bayer pattern and gain, and answers Alpaca
discovery, so Yacana finds it on the WiFi as it finds the Seestar. Needs numpy.

    python tools/replay-camera/replay_camera.py <folder of FITS frames> [--port 32324]
    python tools/replay-camera/replay_camera.py <folder> --focus 1300 [--blur 3.5]

The frames are as the camera took them; the exposure time only sets how long each one takes.

With --focus, the server also has a focuser, of 0 to 2600 steps as the Seestar's, that starts two
steps of 30 out of focus. Each frame is then blurred by how far the focuser is from the best focus
given, as a telescope out of focus: a Gaussian blur of --blur pixels (sigma) per 100 steps, so
the stars' HFR follows a hyperbola and autofocus can be tried.
"""

import argparse
import json
import math
import os
import socket
import struct
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

import numpy as np

DISCOVERY_PORT = 32227
NOT_IMPLEMENTED = 0x400
BAYER_OFFSETS = {"RGGB": (0, 0), "GRBG": (1, 0), "GBRG": (0, 1), "BGGR": (1, 1)}


def read_fits(path):
    """A 16-bit FITS frame as rows from the top, and its keywords."""
    with open(path, "rb") as f:
        data = f.read()
    keys, offset = {}, 0
    while True:
        block = data[offset:offset + 2880]
        offset += 2880
        cards = [block[i:i + 80].decode("ascii", "replace") for i in range(0, 2880, 80)]
        for card in cards:
            if card[8:10] == "= ":
                keys[card[:8].strip()] = card[10:].split("/")[0].strip().strip("'").strip()
        if any(card[:8].strip() == "END" for card in cards):
            break
    if keys.get("BITPIX") != "16":
        raise ValueError(f"{path}: only 16-bit frames")
    width, height = int(keys["NAXIS1"]), int(keys["NAXIS2"])
    pixels = np.frombuffer(data, dtype=">i2", count=width * height, offset=offset).astype(np.int32)
    pixels += int(float(keys.get("BZERO", 0)))
    return pixels.astype(np.uint16).reshape(height, width), keys


def gaussian(plane, sigma):
    """[plane] blurred by a Gaussian of [sigma] pixels, one axis after the other."""
    radius = int(3 * sigma + 0.5)
    if radius < 1:
        return plane.astype(np.float32)
    x = np.arange(-radius, radius + 1)
    kernel = np.exp(-x * x / (2 * sigma * sigma))
    kernel /= kernel.sum()
    out = plane.astype(np.float32)
    height, width = out.shape
    padded = np.pad(out, ((radius, radius), (0, 0)), mode="reflect")
    out = sum(w * padded[i:i + height, :] for i, w in enumerate(kernel))
    padded = np.pad(out, ((0, 0), (radius, radius)), mode="reflect")
    return sum(w * padded[:, i:i + width] for i, w in enumerate(kernel))


def defocus(image, sigma, bayer):
    """The frame out of focus: each colour of the Bayer pattern blurred on its own, so it stays a mosaic."""
    out = image.astype(np.float32)
    if bayer:
        for y in (0, 1):
            for x in (0, 1):
                out[y::2, x::2] = gaussian(image[y::2, x::2], sigma / 2)
    else:
        out = gaussian(image, sigma)
    return np.clip(np.rint(out), 0, 65535).astype(np.uint16)


class Focuser:
    """An absolute focuser of 0 to MAX_STEP steps that moves at SPEED steps a second."""

    MAX_STEP = 2600
    SPEED = 400

    def __init__(self, best):
        self.best = best
        self.connected = False
        self.lock = threading.Lock()
        self.origin = self.target = min(best + 60, self.MAX_STEP)
        self.moved_at = time.time()

    def position(self):
        travel = self.target - self.origin
        done = min(abs(travel), (time.time() - self.moved_at) * self.SPEED)
        return round(self.origin + math.copysign(done, travel))

    def move(self, target):
        with self.lock:
            self.origin = self.position()
            self.target = max(0, min(self.MAX_STEP, target))
            self.moved_at = time.time()
            print(f"focuser to {self.target}", flush=True)

    def halt(self):
        with self.lock:
            self.origin = self.target = self.position()

    def values(self):
        position = self.position()
        return {
            "connected": self.connected, "connecting": False, "name": "Replay focuser", "description": "Blurs the replayed frames",
            "driverinfo": "Yacana replay camera", "driverversion": "1.0", "interfaceversion": 3, "supportedactions": [],
            "absolute": True, "maxstep": self.MAX_STEP, "maxincrement": self.MAX_STEP, "position": position,
            "ismoving": position != self.target, "temperature": 12.4, "tempcomp": False, "tempcompavailable": False,
        }


class Camera:
    """The camera's state: the frames to play, one exposure at a time."""

    def __init__(self, folder, focuser=None, blur=0.0):
        self.frames = sorted(os.path.join(folder, n) for n in os.listdir(folder) if n.lower().endswith((".fit", ".fits")))
        if not self.frames:
            raise SystemExit(f"No FITS frames in {folder}")
        first, keys = read_fits(self.frames[0])
        self.height, self.width = first.shape
        self.pixel_size = float(keys.get("XPIXSZ", 2.9))
        self.bayer = keys.get("BAYERPAT")
        self.gain = int(float(keys.get("GAIN", 0)))
        self.focuser = focuser
        self.blur = blur
        self.connected = False
        self.next = 0
        self.lock = threading.Lock()
        self.started = None
        self.duration = 0.0
        self.image = None

    def start(self, duration):
        with self.lock:
            path = self.frames[self.next % len(self.frames)]
            self.next += 1
            self.image, _ = read_fits(path)
            self.started = time.time()
            self.duration = duration
            note = ""
            if self.focuser:
                position = self.focuser.position()
                sigma = self.blur * abs(position - self.focuser.best) / 100
                self.image = defocus(self.image, sigma, self.bayer)
                note = f" at {position}, blurred {sigma:.1f} px"
            print(f"exposing {duration} s: {os.path.basename(path)}{note}", flush=True)

    def ready(self):
        return self.started is not None and time.time() >= self.started + self.duration

    def state(self):
        return 2 if self.started is not None and not self.ready() else 0

    def values(self):
        offset_x, offset_y = BAYER_OFFSETS.get(self.bayer, (0, 0))
        started = datetime.fromtimestamp(self.started, timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] if self.started else None
        values = {
            "connected": self.connected, "connecting": False, "name": "Replay camera", "description": "Real frames from a folder",
            "driverinfo": "Yacana replay camera", "driverversion": "1.0", "interfaceversion": 3, "supportedactions": [],
            "sensortype": 2 if self.bayer else 0, "sensorname": "", "cameraxsize": self.width, "cameraysize": self.height,
            "pixelsizex": self.pixel_size, "pixelsizey": self.pixel_size, "bayeroffsetx": offset_x, "bayeroffsety": offset_y,
            "maxbinx": 1, "maxbiny": 1, "binx": 1, "biny": 1, "canasymmetricbin": False, "gain": self.gain, "gainmin": 0,
            "gainmax": 600, "offsetmin": 0, "offsetmax": 0, "exposuremin": 0.001, "exposuremax": 3600.0, "exposureresolution": 0.0,
            "canabortexposure": True, "canstopexposure": True, "cansetccdtemperature": False, "ccdtemperature": 20.0,
            "cangetcoolerpower": False, "cooleron": False, "maxadu": 65535, "readoutmodes": ["Default"], "readoutmode": 0,
            "camerastate": self.state(), "imageready": self.ready(), "percentcompleted": 100 if self.ready() else 0,
            "lastexposureduration": self.duration, "startx": 0, "starty": 0, "numx": self.width, "numy": self.height,
            "hasshutter": False, "electronsperadu": 1.0, "fullwellcapacity": 65535.0, "canpulseguide": False,
            "canfastreadout": False, "ispulseguiding": False,
        }
        if started:
            values["lastexposurestarttime"] = started
        return values


def handler(camera):
    transaction = [0]
    devices = [{"DeviceName": "Replay camera", "DeviceType": "Camera", "DeviceNumber": 0, "UniqueID": "yacana-replay-camera"}]
    if camera.focuser:
        devices.append({"DeviceName": "Replay focuser", "DeviceType": "Focuser", "DeviceNumber": 0, "UniqueID": "yacana-replay-focuser"})

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def reply(self, value=None, error=0, message=""):
            transaction[0] += 1
            body = {"ClientTransactionID": int(self.params.get("ClientTransactionID", ["0"])[0] or 0),
                    "ServerTransactionID": transaction[0], "ErrorNumber": error, "ErrorMessage": message}
            if value is not None:
                body["Value"] = value
            data = json.dumps(body).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def image_bytes(self):
            transaction[0] += 1
            client = int(self.params.get("ClientTransactionID", ["0"])[0] or 0)
            header = struct.pack("<11i", 1, 0, client, transaction[0], 44, 2, 8, 2, camera.width, camera.height, 0)
            # Alpaca sends a column at a time: x outside, y inside.
            data = header + np.ascontiguousarray(camera.image.T).astype("<u2").tobytes()
            self.send_response(200)
            self.send_header("Content-Type", "application/imagebytes")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def device(self, path):
            """The camera or the focuser that [path] is for, if there is one."""
            if path.startswith("/api/v1/camera/0/"):
                return camera
            if path.startswith("/api/v1/focuser/0/") and camera.focuser:
                return camera.focuser
            return None

        def do_GET(self):
            url = urlparse(self.path)
            query = {k.lower(): v for k, v in parse_qs(url.query).items()}
            self.params = {"ClientTransactionID": query.get("clienttransactionid", ["0"])}
            path = url.path.lower().rstrip("/")
            if path == "/management/apiversions":
                return self.reply([1])
            if path == "/management/v1/description":
                return self.reply({"ServerName": "Yacana replay camera", "Manufacturer": "Yacana", "ManufacturerVersion": "1.0", "Location": ""})
            if path == "/management/v1/configureddevices":
                return self.reply(devices)
            member = path.rsplit("/", 1)[-1]
            device = self.device(path)
            if device is None:
                return self.reply(error=NOT_IMPLEMENTED, message=f"{path} is not here")
            if device is camera and member == "imagearray":
                if camera.image is None or not camera.ready():
                    return self.reply(error=0x40B, message="No image is ready")
                if "application/imagebytes" in (self.headers.get("Accept") or ""):
                    return self.image_bytes()
                return self.reply(error=NOT_IMPLEMENTED, message="Only ImageBytes")
            values = device.values()
            if member in values:
                return self.reply(values[member])
            return self.reply(error=NOT_IMPLEMENTED, message=f"{member} is not implemented")

        def do_PUT(self):
            url = urlparse(self.path)
            length = int(self.headers.get("Content-Length", 0))
            form = {k.lower(): v for k, v in parse_qs(self.rfile.read(length).decode()).items()}
            self.params = {"ClientTransactionID": form.get("clienttransactionid", ["0"])}
            path = url.path.lower().rstrip("/")
            member = path.rsplit("/", 1)[-1]
            device = self.device(path)
            if device is None:
                return self.reply(error=NOT_IMPLEMENTED, message=f"{path} is not here")
            if member == "connected":
                device.connected = form.get("connected", ["false"])[0].lower() == "true"
            elif device is camera.focuser:
                if member == "move":
                    camera.focuser.move(int(form.get("position", ["0"])[0]))
                elif member == "halt":
                    camera.focuser.halt()
            elif member == "startexposure":
                camera.start(float(form.get("duration", ["1"])[0]))
            elif member in ("abortexposure", "stopexposure"):
                camera.duration = 0.0
            elif member == "gain":
                camera.gain = int(form.get("gain", [camera.gain])[0])
            return self.reply()

    return Handler


def discovery(sock, port):
    """Answers Alpaca discovery broadcasts with this server's port."""
    while True:
        data, address = sock.recvfrom(1024)
        if data.startswith(b"alpacadiscovery1"):
            sock.sendto(json.dumps({"AlpacaPort": port}).encode(), address)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("folder")
    parser.add_argument("--port", type=int, default=32324)
    parser.add_argument("--focus", type=int, help="also serve a focuser, with the best focus at this position")
    parser.add_argument("--blur", type=float, default=3.5, help="blur (sigma, pixels) per 100 steps out of focus")
    args = parser.parse_args()
    focuser = Focuser(args.focus) if args.focus is not None else None
    camera = Camera(args.folder, focuser, args.blur)
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("", DISCOVERY_PORT))
        threading.Thread(target=discovery, args=(sock, args.port), daemon=True).start()
    except OSError as e:
        # Another Alpaca server on this computer, such as OmniSim, may hold the discovery port.
        print(f"No discovery ({e}); add the camera by IP and port", flush=True)
    print(f"Replay camera on port {args.port}: {len(camera.frames)} frames, {camera.width} × {camera.height}, {camera.bayer or 'mono'}", flush=True)
    if focuser:
        print(f"Focuser at {focuser.target}, best focus at {focuser.best}, {args.blur} px of blur per 100 steps", flush=True)
    ThreadingHTTPServer(("", args.port), handler(camera)).serve_forever()


if __name__ == "__main__":
    main()
