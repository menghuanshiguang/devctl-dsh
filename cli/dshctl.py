#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
dshctl - drive a DSH instance running on another device.

Single-file, zero-dependency Python 3 client for the `devctl-dsh` Host plugin
that ships with this bundle. Speaks JSON Lines over one TCP socket, authenticated
by a shared token. Every subcommand prints either a terminal view or, with
`--json`, a structured result an agent can consume.

  dshctl add desk --host 192.168.1.10 --port 7788 --token <TOKEN>
  dshctl ping
  dshctl workspaces
  dshctl sessions
  dshctl send "run the test suite and fix what breaks"
  dshctl send "what is wrong in this screenshot?" --image shot.png
  dshctl permissions danger-full-access
  dshctl watch

Security: the token is a bearer credential for the whole DSH instance. Keep the
port on a trusted LAN or a VPN; never port-forward it to the public internet.
"""

import argparse
import base64
import json
import os
import socket
import sys
import time

VERSION = "1.1.0"
PROTOCOL = 1
DEFAULT_PORT = 7788
DEFAULT_TIMEOUT = 30.0
CONNECT_TIMEOUT = 10.0
CONFIG_PATH = os.environ.get("DSHCTL_HOME") or os.path.join(os.path.expanduser("~"), ".dshctl.json")
# The Host plugin persists its control token here; the legacy name is read once
# so clients paired before the rename keep working.
STATE_NAME = "devctl-dsh.json"
LEGACY_STATE_NAME = "dsh-remote.json"
# Prompt images travel inline as base64; the media type comes from the suffix.
IMAGE_TYPES = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
               ".webp": "image/webp", ".gif": "image/gif"}


# --------------------------------------------------------------------------- #
# terminal
# --------------------------------------------------------------------------- #

def _enable_utf8():
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass


def _color_enabled():
    if os.environ.get("NO_COLOR"):
        return False
    try:
        return bool(sys.stdout.isatty())
    except Exception:
        return False


COLOR = _color_enabled()


def paint(text, code):
    return "\033[%sm%s\033[0m" % (code, text) if COLOR else text


def dim(text):
    return paint(text, "2")


def bold(text):
    return paint(text, "1")


def green(text):
    return paint(text, "32")


def red(text):
    return paint(text, "31")


def yellow(text):
    return paint(text, "33")


def cyan(text):
    return paint(text, "36")


def note(message):
    """Progress chatter belongs on stderr so stdout stays pipeable."""
    sys.stderr.write(dim(message) + "\n")
    sys.stderr.flush()


def emit_json(payload):
    sys.stdout.write(json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
    sys.stdout.flush()


# --------------------------------------------------------------------------- #
# errors
# --------------------------------------------------------------------------- #

class CliError(Exception):
    pass


class RemoteError(Exception):
    def __init__(self, code, message):
        Exception.__init__(self, message)
        self.code = code
        self.message = message


class _Idle(Exception):
    """Internal: no frame arrived inside the poll window. Never user-visible."""


# --------------------------------------------------------------------------- #
# local config
# --------------------------------------------------------------------------- #

def load_config():
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as handle:
            data = json.load(handle)
        if not isinstance(data, dict):
            return {"devices": {}}
        if not isinstance(data.get("devices"), dict):
            data["devices"] = {}
        return data
    except (IOError, OSError, ValueError):
        return {"devices": {}}


def save_config(config):
    directory = os.path.dirname(CONFIG_PATH)
    if directory and not os.path.isdir(directory):
        try:
            os.makedirs(directory)
        except OSError:
            pass
    temporary = CONFIG_PATH + ".tmp"
    with open(temporary, "w", encoding="utf-8") as handle:
        json.dump(config, handle, indent=2, ensure_ascii=False)
        handle.write("\n")
    os.replace(temporary, CONFIG_PATH)


def discover_token():
    """Same-machine convenience: read the token the Host plugin persisted."""
    home = os.environ.get("DSH_HOME") or os.path.join(os.path.expanduser("~"), ".dsh")
    for name in (STATE_NAME, LEGACY_STATE_NAME):
        path = os.path.join(home, name)
        try:
            with open(path, "r", encoding="utf-8") as handle:
                data = json.load(handle)
        except (IOError, OSError, ValueError):
            continue
        token = data.get("token") if isinstance(data, dict) else None
        if isinstance(token, str) and token:
            return token
    return None


def parse_target(text):
    """Accept `host`, `host:port`, `[v6]:port`, or `[v6]`."""
    text = text.strip()
    if text.startswith("["):
        host, _, rest = text[1:].partition("]")
        port = rest.lstrip(":")
    elif text.count(":") == 1:
        host, _, port = text.partition(":")
    else:
        host, port = text, ""
    host = host.strip()
    port = port.strip()
    if not host:
        raise CliError("target needs a host, e.g. 192.168.1.10:%d" % DEFAULT_PORT)
    if port:
        if not port.isdigit():
            raise CliError("invalid port %r" % port)
        return host, int(port)
    return host, DEFAULT_PORT


def resolve_device(config, name):
    devices = config.get("devices") or {}
    if not devices:
        raise CliError("no device registered yet - run: dshctl add <name> --host <HOST> --port 7788 --token <TOKEN>")
    if name:
        if name not in devices:
            raise CliError("unknown device %r (registered: %s)" % (name, ", ".join(sorted(devices))))
        return name, devices[name]
    default = config.get("default")
    if default and default in devices:
        return default, devices[default]
    if len(devices) == 1:
        only = sorted(devices)[0]
        return only, devices[only]
    raise CliError("several devices registered - choose one with --device (registered: %s)" % ", ".join(sorted(devices)))


def remember_session(config, device_name, session_id):
    if not session_id:
        return
    config.setdefault("lastSession", {})[device_name] = session_id
    try:
        save_config(config)
    except (IOError, OSError) as exc:
        note("could not persist lastSession: %s" % exc)


# --------------------------------------------------------------------------- #
# wire client
# --------------------------------------------------------------------------- #

class Client(object):
    """One authenticated JSON-Lines connection to the remote DSH."""

    def __init__(self, device, timeout=DEFAULT_TIMEOUT):
        self.host = device.get("host")
        self.port = int(device.get("port") or DEFAULT_PORT)
        self.token = device.get("token") or ""
        self.timeout = timeout
        self.sock = None
        self.buffer = b""
        self.counter = 0
        self.info = None

    def __enter__(self):
        return self.connect()

    def __exit__(self, *_exc):
        self.close()
        return False

    def connect(self):
        try:
            self.sock = socket.create_connection((self.host, self.port), min(self.timeout, CONNECT_TIMEOUT))
        except socket.error as exc:
            raise CliError("cannot reach %s:%d - %s" % (self.host, self.port, exc))
        self.sock.settimeout(self.timeout)
        self.info = self.request("hello", {"token": self.token, "client": "dshctl/%s" % VERSION})
        return self

    def close(self):
        """Half-close and drain before closing.

        A plain close() while unread frames sit in the receive buffer makes the
        kernel send RST instead of FIN, which discards whatever we had just
        asked the server for. Shutting down the write side first lets the peer
        finish and end the stream cleanly.
        """
        if self.sock is None:
            return
        try:
            self.sock.shutdown(socket.SHUT_WR)
        except socket.error:
            pass
        try:
            self.sock.settimeout(0.5)
            while self.sock.recv(65536):
                pass
        except socket.error:
            pass
        try:
            self.sock.close()
        except socket.error:
            pass
        self.sock = None

    # -- framing ------------------------------------------------------------ #

    def _send(self, request_id, method, params):
        payload = {"id": request_id, "method": method, "params": params or {}}
        data = (json.dumps(payload, ensure_ascii=False) + "\n").encode("utf-8")
        try:
            self.sock.sendall(data)
        except socket.error as exc:
            raise CliError("connection lost while sending: %s" % exc)

    def _read_frame(self, timeout):
        """Read one frame, preserving a partial line across timeouts."""
        self.sock.settimeout(timeout)
        while True:
            index = self.buffer.find(b"\n")
            if index >= 0:
                raw = self.buffer[:index]
                self.buffer = self.buffer[index + 1:]
                text = raw.decode("utf-8", "replace").strip()
                if not text:
                    continue
                try:
                    return json.loads(text)
                except ValueError:
                    raise CliError("malformed frame from server: %r" % text[:200])
            try:
                chunk = self.sock.recv(65536)
            except socket.timeout:
                raise _Idle()
            except socket.error as exc:
                raise CliError("connection lost: %s" % exc)
            if not chunk:
                raise CliError("the DSH side closed the connection (token rejected?)")
            self.buffer += chunk

    def begin(self, method, params=None):
        self.counter += 1
        request_id = self.counter
        self._send(request_id, method, params)
        return request_id

    @staticmethod
    def _unwrap(frame):
        if frame.get("ok"):
            return frame.get("result")
        error = frame.get("error") or {}
        raise RemoteError(error.get("code") or "error", error.get("message") or "unspecified failure")

    def request(self, method, params=None, timeout=None, on_event=None):
        if self.sock is None:
            raise CliError("not connected")
        timeout = self.timeout if timeout is None else timeout
        request_id = self.begin(method, params)
        while True:
            try:
                frame = self._read_frame(timeout)
            except _Idle:
                raise CliError("timed out after %.0fs waiting for %s" % (timeout, method))
            if "evt" in frame:
                if on_event is not None:
                    on_event(frame)
                continue
            if frame.get("id") != request_id:
                continue
            return self._unwrap(frame)

    def watch(self, session_id, on_event, deadline):
        """Attach to `sessions.watch` and pump events until `deadline`.

        `on_event` may return False to detach early.
        """
        request_id = self.begin("sessions.watch", {"sessionId": session_id})
        while True:
            remaining = deadline - time.time()
            if remaining <= 0:
                return
            try:
                frame = self._read_frame(min(remaining, 5.0))
            except _Idle:
                continue
            if "evt" in frame:
                if on_event(frame) is False:
                    return
                continue
            if frame.get("id") == request_id:
                self._unwrap(frame)


# --------------------------------------------------------------------------- #
# sessions
# --------------------------------------------------------------------------- #

def _compact(session_id):
    value = (session_id or "").lower()
    if value.startswith("session-"):
        value = value[len("session-"):]
    return value.replace("-", "")


def short_id(session_id):
    core = _compact(session_id)
    if len(core) <= 13:
        return core or "?"
    return core[:8] + "\u2026" + core[-4:]


def human_age(updated_at):
    if not isinstance(updated_at, (int, float)):
        return "?"
    delta = max(0.0, time.time() - updated_at / 1000.0)
    if delta < 60:
        return "%ds" % int(delta)
    if delta < 3600:
        return "%dm" % int(delta / 60)
    if delta < 86400:
        return "%dh" % int(delta / 3600)
    return "%dd" % int(delta / 86400)


def _human_duration(milliseconds):
    if not isinstance(milliseconds, (int, float)) or milliseconds < 0:
        return "?"
    seconds = int(milliseconds / 1000)
    if seconds < 60:
        return "%ds" % seconds
    if seconds < 3600:
        return "%dm %ds" % (seconds // 60, seconds % 60)
    return "%dh %dm" % (seconds // 3600, (seconds % 3600) // 60)


def fetch_sessions(client, timeout=None):
    result = client.request("sessions.list", {}, timeout=timeout)
    items = result.get("items") if isinstance(result, dict) else None
    return items or []


def resolve_session(client, config, device_name, reference, timeout=None):
    items = fetch_sessions(client, timeout)
    if not items:
        raise CliError("that device has no Sessions yet - create one with: dshctl new")

    if not reference:
        remembered = (config.get("lastSession") or {}).get(device_name)
        if remembered:
            for item in items:
                if item.get("sessionId") == remembered:
                    return item
        for item in items:
            if item.get("origin") != "subagent":
                return item
        return items[0]

    for item in items:
        if item.get("sessionId") == reference:
            return item

    raw = reference.strip()
    for separator in ("\u2026", "..."):
        if separator in raw:
            head, _, tail = raw.partition(separator)
            head = _compact(head)
            tail = _compact(tail)
            matches = [i for i in items if _compact(i.get("sessionId")).startswith(head)
                       and _compact(i.get("sessionId")).endswith(tail)]
            if len(matches) == 1:
                return matches[0]
            if not matches:
                raise CliError("no Session matches %r (try: dshctl sessions)" % reference)
            raise CliError("%r matches %d Sessions - be more specific" % (reference, len(matches)))

    needle = _compact(raw)
    matches = [i for i in items if needle and needle in _compact(i.get("sessionId"))]
    if len(matches) == 1:
        return matches[0]
    if not matches:
        raise CliError("no Session matches %r (try: dshctl sessions)" % reference)
    raise CliError("%r matches %d Sessions - be more specific" % (reference, len(matches)))


# --------------------------------------------------------------------------- #
# rendering
# --------------------------------------------------------------------------- #

def _flatten_model(value):
    """Tolerate either "provider/model" or a raw {lastUsed, next} projection."""
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        chosen = value.get("next") or value.get("lastUsed")
        if isinstance(chosen, dict):
            provider = chosen.get("provider")
            model = chosen.get("model")
            if isinstance(provider, str) and isinstance(model, str):
                return "%s/%s" % (provider, model)
            if isinstance(model, str):
                return model
    return None


def print_sessions(items):
    if not items:
        sys.stdout.write("no Sessions\n")
        return
    header = "  %-13s %5s  %s" % ("SESSION", "AGE", "TITLE")
    sys.stdout.write(dim(header) + "\n")
    for item in items:
        running = item.get("running") is True
        marker = green("\u25cf") if running else dim("\u00b7")
        tree = "\u21b3 " if item.get("origin") == "subagent" else ""
        title = item.get("title") or ("(untitled)" if not item.get("blank") else "(new)")
        title = title.replace("\n", " ").strip()
        if len(title) > 60:
            title = title[:59] + "\u2026"
        sys.stdout.write("%s %-13s %5s  %s%s\n" % (marker, short_id(item.get("sessionId")), human_age(item.get("updatedAt")), tree, title))
        cwd = item.get("cwd")
        model = _flatten_model(item.get("model"))
        preset = _flatten_permissions(item.get("permissions"))
        if cwd or model or preset:
            sys.stdout.write("    %s\n" % dim("  ".join(part for part in (cwd, model, preset) if part)))
    sys.stdout.flush()


def _prefix(label, code):
    padded = "%-4s" % label
    return paint(padded, code) if COLOR else padded


def print_body(label, code, body):
    body = (body or "").strip()
    if not body:
        return
    lines = body.splitlines()
    sys.stdout.write("%s %s\n" % (_prefix(label, code), lines[0]))
    for line in lines[1:]:
        sys.stdout.write("     %s\n" % line)
    sys.stdout.flush()


def render_record(record, detail=False):
    kind = record.get("kind")
    if kind == "user":
        print_body("you", "36", record.get("text"))
    elif kind == "assistant":
        print_body("ai", "32", record.get("text"))
    elif kind == "tool-call":
        print_body("call", "33", "%s %s" % (record.get("name") or "?", record.get("arguments") or ""))
    elif kind == "tool-result":
        error = record.get("error")
        if error:
            print_body("err", "31", record.get("text") or json.dumps(error, ensure_ascii=False))
        else:
            print_body("ret", "33", record.get("text"))
    elif kind == "turn-end":
        if detail:
            reason = record.get("reason") or "?"
            print_body("turn", "2", "ended: %s" % reason)
    elif detail:
        print_body("evt", "2", record.get("type") or kind or "?")


def render_tail(result):
    records = result.get("records") or []
    if not records:
        sys.stdout.write(dim("(no messages)\n"))
        return
    for record in records:
        render_record(record)


# --------------------------------------------------------------------------- #
# commands
# --------------------------------------------------------------------------- #

def cmd_add(args, config, opts):
    if args.target and args.host:
        raise CliError("give the address either as `target` or as --host, not both")
    raw = args.host or args.target
    if not raw:
        raise CliError("address required - `dshctl add <name> <host:port>` or `dshctl add <name> --host <host> [--port %d]`"
                       % DEFAULT_PORT)
    host, port = parse_target(raw)
    if args.port is not None:
        port = args.port
    token = args.token or discover_token()
    if not token:
        raise CliError("no token supplied and none found at $DSH_HOME/%s - pass --token" % STATE_NAME)
    device = {"type": args.type, "host": host, "port": port, "token": token}
    devices = config.setdefault("devices", {})
    devices[args.name] = device
    if args.default or len(devices) == 1:
        config["default"] = args.name
    save_config(config)

    result = {"device": args.name, "type": args.type, "host": host, "port": port,
              "tokenSource": "argument" if args.token else "local state file",
              "config": CONFIG_PATH, "verified": False}
    try:
        with Client(device, opts["timeout"]) as client:
            result["verified"] = True
            result["server"] = client.info
    except (CliError, RemoteError) as exc:
        result["warning"] = str(exc)

    if not opts["json"]:
        sys.stdout.write("registered %s \u2192 %s:%d\n" % (bold(args.name), host, port))
        sys.stdout.write("config    %s\n" % CONFIG_PATH)
        if result["verified"]:
            server = result["server"] or {}
            sys.stdout.write("reached   %s (%s), devctl-dsh %s\n" % (server.get("host"), server.get("platform"), server.get("version")))
        else:
            sys.stdout.write("%s\n" % yellow("unverified: %s" % result["warning"]))
        if config.get("default") == args.name:
            sys.stdout.write("default   yes\n")
        sys.stdout.flush()
    return result


def cmd_devices(args, config, opts):
    devices = config.get("devices") or {}
    default = config.get("default")
    rows = []
    for name in sorted(devices):
        device = devices[name]
        rows.append({"name": name, "type": device.get("type") or "dsh", "host": device.get("host"),
                     "port": device.get("port") or DEFAULT_PORT, "default": name == default})
    if not opts["json"]:
        if not rows:
            sys.stdout.write("no device registered - run: dshctl add <name> --host <HOST> --port %d --token <TOKEN>\n"
                             % DEFAULT_PORT)
        for row in rows:
            marker = green("*") if row["default"] else " "
            sys.stdout.write("%s %-12s %-4s %s:%d\n" % (marker, row["name"], row["type"], row["host"], row["port"]))
        sys.stdout.write(dim("config %s\n" % CONFIG_PATH))
        sys.stdout.flush()
    return {"devices": rows, "default": default, "config": CONFIG_PATH}


def cmd_rm(args, config, opts):
    devices = config.get("devices") or {}
    if args.name not in devices:
        raise CliError("unknown device %r" % args.name)
    del devices[args.name]
    if config.get("default") == args.name:
        config["default"] = sorted(devices)[0] if devices else None
    (config.get("lastSession") or {}).pop(args.name, None)
    save_config(config)
    if not opts["json"]:
        sys.stdout.write("removed %s\n" % args.name)
    return {"removed": args.name, "default": config.get("default")}


def cmd_use(args, config, opts):
    devices = config.get("devices") or {}
    if args.name not in devices:
        raise CliError("unknown device %r" % args.name)
    config["default"] = args.name
    save_config(config)
    if not opts["json"]:
        sys.stdout.write("default device is now %s\n" % bold(args.name))
    return {"default": args.name}


def cmd_ping(args, config, opts):
    name, device = resolve_device(config, opts["device"])
    started = time.time()
    client = Client(device, opts["timeout"]).connect()
    try:
        pong = client.request("ping")
    finally:
        client.close()
    latency = int((time.time() - started) * 1000)
    result = {"device": name, "latencyMs": latency, "server": client.info, "pong": pong}
    if not opts["json"]:
        server = client.info or {}
        sys.stdout.write("%s %s:%d\n" % (green("ok"), device.get("host"), device.get("port") or DEFAULT_PORT))
        sys.stdout.write("host      %s (%s)\n" % (server.get("host"), server.get("platform")))
        sys.stdout.write("protocol  %d   devctl-dsh %s\n" % (server.get("protocol", PROTOCOL), server.get("version")))
        sys.stdout.write("uptime    %s\n" % _human_duration(pong.get("uptimeMs")))
        sys.stdout.write("latency   %d ms\n" % latency)
        sys.stdout.flush()
    return result


def cmd_sessions(args, config, opts):
    name, device = resolve_device(config, opts["device"])
    client = Client(device, opts["timeout"]).connect()
    try:
        items = fetch_sessions(client)
    finally:
        client.close()
    if args.roots:
        items = [i for i in items if i.get("origin") != "subagent"]
    total = len(items)
    limit = args.limit if isinstance(args.limit, int) else 25
    shown = items if limit <= 0 else items[:limit]
    if not opts["json"]:
        print_sessions(shown)
        if len(shown) < total:
            sys.stdout.write(dim("... %d more (use -n 0 for all)\n" % (total - len(shown))))
            sys.stdout.flush()
    return {"device": name, "items": shown, "total": total}


def cmd_new(args, config, opts):
    name, device = resolve_device(config, opts["device"])
    params = {}
    if args.cwd:
        params["cwd"] = args.cwd
    if args.preset:
        params["agentPreset"] = args.preset
    client = Client(device, opts["timeout"]).connect()
    try:
        if args.workspace:
            params["workspaceId"] = resolve_workspace(client, config, name, args.workspace)["workspaceId"]
        elif (config.get("lastWorkspace") or {}).get(name):
            params["workspaceId"] = (config.get("lastWorkspace") or {})[name]
        try:
            created = client.request("sessions.create", params)
        except RemoteError:
            if "workspaceId" not in params or args.workspace:
                raise
            # The remembered Workspace is gone; fall back to the Host default.
            (config.get("lastWorkspace") or {}).pop(name, None)
            save_config(config)
            params.pop("workspaceId")
            created = client.request("sessions.create", params)
    finally:
        client.close()
    session_id = created.get("sessionId") if isinstance(created, dict) else None
    remember_session(config, name, session_id)
    result = {"device": name, "sessionId": session_id, "workspaceId": params.get("workspaceId"),
              "created": created}
    if not opts["json"]:
        sys.stdout.write("%s %s\n" % (green("created"), short_id(session_id)))
        if params.get("workspaceId"):
            sys.stdout.write("workspace %s\n" % short_workspace_id(params["workspaceId"]))
        if created.get("agentPreset"):
            sys.stdout.write("preset    %s\n" % created.get("agentPreset"))
        sys.stdout.flush()
    return result


def _parse_selector(value):
    if not value:
        return None
    separator = "/" if "/" in value else ":"
    if separator not in value:
        raise CliError("expected PROVIDER%sMODEL, got %r" % (separator, value))
    provider, _, model = value.partition(separator)
    return {"provider": provider.strip(), "model": model.strip()}


def cmd_models(args, config, opts):
    name, device = resolve_device(config, opts["device"])
    client = Client(device, opts["timeout"]).connect()
    try:
        if args.select:
            selection = _parse_selector(args.select)
            if args.effort:
                selection["reasoningEffort"] = args.effort
            session = resolve_session(client, config, name, args.session)
            applied = client.request("models.select", dict(selection, sessionId=session["sessionId"]))
            payload = {"device": name, "sessionId": session["sessionId"], "selected": applied}
        else:
            catalog = client.request("models.catalog")
            payload = {"device": name, "catalog": catalog}
    finally:
        client.close()

    if not opts["json"]:
        if args.select:
            sys.stdout.write("%s %s on %s\n" % (green("selected"), args.select, short_id(payload["sessionId"])))
        else:
            _print_catalog(payload.get("catalog"))
    return payload


def _print_catalog(catalog):
    """Render a ModelCatalog without assuming more shape than it guarantees."""
    if not isinstance(catalog, dict):
        sys.stdout.write(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")
        return
    current = catalog.get("default") or catalog.get("current") or {}
    if not isinstance(current, dict):
        current = {}
    if current:
        effort = current.get("reasoningEffort")
        suffix = "  effort=%s" % effort if effort else ""
        sys.stdout.write("current   %s/%s%s\n" % (current.get("provider"), current.get("model"), suffix))
    default_model = current.get("model")
    groups = catalog.get("groups") or catalog.get("providers") or catalog.get("items") or []
    if isinstance(groups, dict):
        groups = [dict(value, provider=key) if isinstance(value, dict) else {"provider": key, "models": value}
                  for key, value in groups.items()]
    for group in groups:
        if not isinstance(group, dict):
            continue
        label = group.get("provider") or group.get("id") or group.get("name") or "?"
        models = group.get("models") or group.get("items") or []
        if isinstance(models, dict):
            models = list(models.keys())
        sys.stdout.write("%s\n" % bold(label))
        for model in models:
            if isinstance(model, str):
                model_id, model_name = model, None
            elif isinstance(model, dict):
                model_id = model.get("id") or model.get("model") or model.get("name") or "?"
                model_name = model.get("name")
            else:
                continue
            marker = " *" if model_id == default_model else ""
            trailing = "  %s" % dim(model_name) if model_name and model_name != model_id else ""
            sys.stdout.write("  %s%s%s\n" % (model_id, marker, trailing))
    if not groups:
        sys.stdout.write(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")
    routable = catalog.get("routableProviders")
    if isinstance(routable, list) and routable:
        sys.stdout.write(dim("routable  %s\n" % ", ".join(str(entry) for entry in routable)))
    failures = catalog.get("failures")
    if isinstance(failures, list):
        for failure in failures:
            sys.stdout.write("%s %s\n" % (yellow("failure"), json.dumps(failure, ensure_ascii=False)))


# --------------------------------------------------------------------------- #
# workspaces
# --------------------------------------------------------------------------- #

def fetch_workspaces(client, timeout=None):
    result = client.request("workspaces.list", None, timeout)
    return (result or {}).get("items") or []


def _compact_workspace(workspace_id):
    value = (workspace_id or "").lower()
    for prefix in ("wks-", "workspace-"):
        if value.startswith(prefix):
            value = value[len(prefix):]
            break
    return value.replace("-", "")


def short_workspace_id(workspace_id):
    value = workspace_id or ""
    if len(value) <= 17:
        return value
    return "%s\u2026%s" % (value[:8], value[-4:])


def resolve_workspace(client, config, device_name, reference):
    """Pick a Workspace by full id, id prefix, exact title, or the remembered one."""
    workspaces = fetch_workspaces(client)
    if not workspaces:
        raise CliError("this Host has no Workspace yet (create one: dshctl ws-new <PATH>)")
    if reference:
        wanted = reference.strip()
        for workspace in workspaces:
            if workspace.get("workspaceId") == wanted:
                return workspace
        exact = [w for w in workspaces if (w.get("title") or "").lower() == wanted.lower()]
        if len(exact) == 1:
            return exact[0]
        compact = _compact_workspace(wanted)
        matches = [w for w in workspaces
                   if compact and compact in _compact_workspace(w.get("workspaceId"))]
        if len(matches) == 1:
            return matches[0]
        if not matches:
            raise CliError("no Workspace matches %r (try: dshctl workspaces)" % reference)
        raise CliError("%r matches %d Workspaces - be more specific" % (reference, len(matches)))
    remembered = (config.get("lastWorkspace") or {}).get(device_name)
    if remembered:
        for workspace in workspaces:
            if workspace.get("workspaceId") == remembered:
                return workspace
    return workspaces[0]


def print_workspaces(items):
    if not items:
        sys.stdout.write("no Workspace\n")
        return
    sys.stdout.write(dim("  %-17s %4s  %s\n" % ("WORKSPACE", "SESS", "TITLE")))
    for item in items:
        sessions = item.get("sessionIds") or []
        title = (item.get("title") or "(untitled)").replace("\n", " ").strip()
        sys.stdout.write("  %-17s %4d  %s\n" % (short_workspace_id(item.get("workspaceId")), len(sessions), title))
        if item.get("path"):
            sys.stdout.write("    %s\n" % dim(item.get("path")))
    sys.stdout.flush()


def cmd_workspaces(args, config, opts):
    name, device = resolve_device(config, opts["device"])
    client = Client(device, opts["timeout"]).connect()
    try:
        items = fetch_workspaces(client)
    finally:
        client.close()
    if not opts["json"]:
        print_workspaces(items)
    return {"device": name, "items": items, "total": len(items)}


def cmd_ws_new(args, config, opts):
    name, device = resolve_device(config, opts["device"])
    client = Client(device, opts["timeout"]).connect()
    try:
        created = client.request("workspaces.create", {"path": args.path}) or {}
    finally:
        client.close()
    workspace = created.get("workspace") or {}
    result = {"device": name, "created": created.get("created"), "workspace": workspace}
    if not opts["json"]:
        label = "created" if created.get("created") else "existing"
        sys.stdout.write("%s %s\n" % (green(label), short_workspace_id(workspace.get("workspaceId"))))
        if workspace.get("title"):
            sys.stdout.write("title     %s\n" % workspace.get("title"))
        if workspace.get("path"):
            sys.stdout.write("path      %s\n" % workspace.get("path"))
        sys.stdout.flush()
    return result


def cmd_ws_use(args, config, opts):
    name, device = resolve_device(config, opts["device"])
    client = Client(device, opts["timeout"]).connect()
    try:
        workspace = resolve_workspace(client, config, name, args.workspace)
    finally:
        client.close()
    workspace_id = workspace.get("workspaceId")
    config.setdefault("lastWorkspace", {})[name] = workspace_id
    save_config(config)
    result = {"device": name, "workspace": workspace, "remembered": True}
    if not opts["json"]:
        sys.stdout.write("%s %s  %s\n" % (green("selected"), short_workspace_id(workspace_id),
                                          workspace.get("title") or ""))
        sys.stdout.write(dim("`dshctl new` now creates Sessions in this Workspace\n"))
        sys.stdout.flush()
    return result


def cmd_ws_rename(args, config, opts):
    name, device = resolve_device(config, opts["device"])
    client = Client(device, opts["timeout"]).connect()
    try:
        workspace = resolve_workspace(client, config, name, args.workspace)
        updated = client.request("workspaces.rename",
                                 {"workspaceId": workspace.get("workspaceId"), "title": args.title}) or {}
    finally:
        client.close()
    view = updated.get("workspace") or {}
    result = {"device": name, "workspace": view}
    if not opts["json"]:
        sys.stdout.write("%s %s  %s\n" % (green("renamed"), short_workspace_id(view.get("workspaceId")),
                                          view.get("title") or ""))
        sys.stdout.flush()
    return result


def cmd_ws_rm(args, config, opts):
    name, device = resolve_device(config, opts["device"])
    client = Client(device, opts["timeout"]).connect()
    try:
        workspace = resolve_workspace(client, config, name, args.workspace)
        client.request("workspaces.delete", {"workspaceId": workspace.get("workspaceId")})
    finally:
        client.close()
    workspace_id = workspace.get("workspaceId")
    remembered = config.get("lastWorkspace") or {}
    if remembered.get(name) == workspace_id:
        remembered.pop(name, None)
        save_config(config)
    result = {"device": name, "deleted": workspace_id}
    if not opts["json"]:
        sys.stdout.write("%s %s  %s\n" % (green("removed"), short_workspace_id(workspace_id),
                                          workspace.get("title") or ""))
        sys.stdout.write(dim("its Sessions and files are kept; only the Workspace entry is gone\n"))
        sys.stdout.flush()
    return result


# --------------------------------------------------------------------------- #
# permissions
# --------------------------------------------------------------------------- #

def _flatten_permissions(value):
    """Tolerate either a preset name or a raw {currentValue} projection."""
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        current = value.get("currentValue")
        if isinstance(current, str):
            return current
    return None


def cmd_permissions(args, config, opts):
    name, device = resolve_device(config, opts["device"])
    client = Client(device, opts["timeout"]).connect()
    try:
        catalog = client.request("permissions.catalog") or {}
        payload = {"device": name, "catalog": catalog, "sessionId": None, "preset": None}
        try:
            session = resolve_session(client, config, name, args.session)
        except CliError:
            session = None
        if session is not None:
            payload["sessionId"] = session.get("sessionId")
            if args.preset:
                applied = client.request("permissions.set",
                                         {"sessionId": session.get("sessionId"), "preset": args.preset}) or {}
                payload["preset"] = applied.get("preset")
            else:
                # Reading the projection avoids resuming a cold Session just to look.
                payload["preset"] = _flatten_permissions(session.get("permissions"))
    finally:
        client.close()

    if not opts["json"]:
        preset = payload.get("preset")
        if preset:
            sys.stdout.write("current   %s on %s\n" % (bold(preset), short_id(payload["sessionId"])))
        else:
            sys.stdout.write("current   %s\n" % dim("(no Session resolved; pass -s)" if not payload["sessionId"]
                                                    else "(unknown)"))
        options = catalog.get("options") or []
        for option in options:
            if not isinstance(option, dict):
                continue
            value = option.get("value")
            marker = "*" if value == preset else " "
            description = option.get("description") or option.get("name") or ""
            sys.stdout.write("%s %-22s %s\n" % (marker, value, dim(description) if description else ""))
        if catalog.get("defaultPreset"):
            sys.stdout.write(dim("default   %s\n" % catalog.get("defaultPreset")))
        sys.stdout.flush()
    return payload


def cmd_search(args, config, opts):
    name, device = resolve_device(config, opts["device"])
    client = Client(device, opts["timeout"]).connect()
    try:
        result = client.request("sessions.search", {"query": args.query})
    except RemoteError as error:
        if error.code == "search-disabled":
            raise CliError("session search is off on this Host; list with `dshctl sessions` and read with `dshctl tail -s <id>`")
        raise
    finally:
        client.close()
    items = result.get("items") or []
    if not opts["json"]:
        if not items:
            sys.stdout.write("no match\n")
        for item in items:
            snippet = (item.get("snippet") or "").replace("\n", " ").strip()
            sys.stdout.write("%s  %s\n" % (short_id(item.get("sessionId")), snippet))
        if result.get("hasMore"):
            sys.stdout.write(dim("(more results available)\n"))
        sys.stdout.flush()
    return {"device": name, "query": args.query, "items": items, "hasMore": result.get("hasMore") is True}


def cmd_rename(args, config, opts):
    name, device = resolve_device(config, opts["device"])
    client = Client(device, opts["timeout"]).connect()
    try:
        session = resolve_session(client, config, name, args.session)
        result = client.request("sessions.rename", {"sessionId": session["sessionId"], "title": args.title})
    finally:
        client.close()
    if not opts["json"]:
        sys.stdout.write("%s %s \u2192 %s\n" % (green("renamed"), short_id(session["sessionId"]), args.title))
    return {"device": name, "sessionId": session["sessionId"], "renamed": result}


def cmd_cancel(args, config, opts):
    name, device = resolve_device(config, opts["device"])
    client = Client(device, opts["timeout"]).connect()
    try:
        session = resolve_session(client, config, name, args.session)
        result = client.request("sessions.cancel", {"sessionId": session["sessionId"]})
    finally:
        client.close()
    remember_session(config, name, session["sessionId"])
    if not opts["json"]:
        sys.stdout.write("%s %s\n" % (green("cancelled"), short_id(session["sessionId"])))
    return {"device": name, "sessionId": session["sessionId"], "cancel": result}


def cmd_tail(args, config, opts):
    name, device = resolve_device(config, opts["device"])
    client = Client(device, max(opts["timeout"], 45.0)).connect()
    try:
        session = resolve_session(client, config, name, args.session)
        result = client.request("sessions.tail", {"sessionId": session["sessionId"], "limit": args.number})
    finally:
        client.close()
    remember_session(config, name, session["sessionId"])
    result = dict(result or {})
    result["device"] = name
    result["sessionId"] = session["sessionId"]
    if not opts["json"]:
        sys.stdout.write(dim("session %s  cursor %s\n" % (short_id(session["sessionId"]), result.get("cursor"))))
        render_tail(result)
    return result


def cmd_watch(args, config, opts):
    name, device = resolve_device(config, opts["device"])
    client = Client(device, opts["timeout"]).connect()
    collected = []
    try:
        session = resolve_session(client, config, name, args.session)
        remember_session(config, name, session["sessionId"])
        deadline = time.time() + (args.for_seconds if args.for_seconds else 86400.0)
        base = {"cursor": None}
        if not opts["json"]:
            sys.stdout.write(dim("watching %s - Ctrl-C to stop\n" % short_id(session["sessionId"])))
            sys.stdout.flush()

        def on_event(frame):
            event = frame.get("evt")
            data = frame.get("data") or {}
            if event == "snapshot":
                base["cursor"] = data.get("cursor")
                return
            if event == "delta":
                if opts["json"]:
                    collected.append({"kind": "delta", "text": data.get("text")})
                else:
                    sys.stdout.write(data.get("text") or "")
                    sys.stdout.flush()
                return
            if event == "event":
                if opts["json"]:
                    collected.append(data)
                else:
                    render_record(data, detail=args.detail)
                return
            if event == "watch-end":
                if opts["json"]:
                    collected.append({"kind": "watch-end", "reason": data.get("reason")})
                else:
                    sys.stdout.write("\n" + dim("watch ended: %s\n" % data.get("reason")))
                    sys.stdout.flush()
                return False
            if event == "watch-start" and args.detail and not opts["json"]:
                note("attached")
            return None

        client.watch(session["sessionId"], on_event, deadline)
    finally:
        client.close()
    return {"device": name, "sessionId": session["sessionId"], "events": collected}


def _read_image(path):
    """Read one local image into the base64 part the Host takes inline."""
    extension = os.path.splitext(path)[1].lower()
    media_type = IMAGE_TYPES.get(extension)
    if media_type is None:
        raise CliError("unsupported image type %r (use %s)"
                       % (extension or path, ", ".join(sorted(IMAGE_TYPES))))
    try:
        with open(path, "rb") as handle:
            data = handle.read()
    except IOError as exc:
        raise CliError("cannot read %s: %s" % (path, exc))
    if not data:
        raise CliError("%s is empty" % path)
    return {"mediaType": media_type, "data": base64.b64encode(data).decode("ascii"),
            "name": os.path.basename(path)}


def cmd_send(args, config, opts):
    parts = list(args.text)
    if parts == ["-"]:
        parts = [sys.stdin.read()]
    text = " ".join(parts).strip()
    images = [_read_image(path) for path in (args.image or [])]
    if not text and not images:
        raise CliError("nothing to send")

    name, device = resolve_device(config, opts["device"])
    client = Client(device, max(opts["timeout"], 30.0)).connect()
    collected = []
    streamed = []
    try:
        session = resolve_session(client, config, name, args.session)
        session_id = session["sessionId"]
        remember_session(config, name, session_id)
        was_running = session.get("running") is True

        state = {"base": None, "accepted": False, "prompt_id": None, "turn_started": not was_running,
                 "delta_count": 0, "done": False}

        def on_event(frame):
            event = frame.get("evt")
            data = frame.get("data") or {}
            if event == "snapshot":
                state["base"] = data.get("cursor")
                return
            if event == "watch-start":
                return
            if event == "watch-end":
                state["done"] = True
                return
            if event == "delta":
                chunk = data.get("text") or ""
                if not state["accepted"] or not state["turn_started"]:
                    return
                state["delta_count"] += 1
                streamed.append(chunk)
                if not opts["json"]:
                    sys.stdout.write(chunk)
                    sys.stdout.flush()
                return
            if event != "event":
                return
            seq = data.get("seq")
            if state["base"] is not None and isinstance(seq, int) and seq <= state["base"]:
                return
            if not state["accepted"]:
                return
            kind = data.get("kind")
            if kind == "turn-start":
                state["turn_started"] = True
                if not opts["json"] and streamed:
                    sys.stdout.write("\n")
                return
            if kind == "turn-end":
                if state["turn_started"]:
                    state["done"] = True
                return
            if kind == "assistant" and state["delta_count"] > 0:
                # The step was already streamed token by token; do not echo it whole.
                state["delta_count"] = 0
                return
            if kind == "user":
                # The turn's own prompt plus DSH's runtime-context injections.
                # Never echo those to the terminal; keep them in --json instead.
                if opts["json"]:
                    collected.append(data)
                elif args.detail:
                    render_record(data)
                return
            if opts["json"]:
                collected.append(data)
            else:
                render_record(data)

        # Attach first, then prompt: nothing between the two can be missed.
        client.begin("sessions.watch", {"sessionId": session_id})
        prompt = {"sessionId": session_id, "mode": "steer" if args.steer else "queue", "text": text}
        if images:
            prompt["images"] = images
        prompt_id = client.begin("sessions.prompt", prompt)
        state["prompt_id"] = prompt_id

        # `--no-wait` still has to read the acknowledgement: returning before the
        # server consumed the frame is how prompts get dropped.
        no_wait = args.wait is not None and args.wait <= 0
        budget = 600.0 if args.wait is None else max(args.wait, 0.0)
        deadline = time.time() + budget
        ack_deadline = time.time() + 20.0

        while True:
            if state["done"]:
                break
            now = time.time()
            if not state["accepted"]:
                if now >= ack_deadline:
                    if not opts["json"]:
                        sys.stderr.write("dshctl: the prompt was not acknowledged within 20s\n")
                    break
                window = min(ack_deadline - now, 5.0)
            elif no_wait:
                break
            else:
                if now >= deadline:
                    if not opts["json"]:
                        sys.stdout.write("\n" + yellow("(stopped waiting after %.0fs; the turn may still be running)\n" % budget))
                        sys.stdout.flush()
                    break
                window = min(deadline - now, 5.0)
            try:
                frame = client._read_frame(window)
            except _Idle:
                continue
            if "evt" in frame:
                on_event(frame)
                continue
            if frame.get("id") == prompt_id:
                if frame.get("ok"):
                    state["accepted"] = True
                    if not opts["json"]:
                        note("queued" + ("" if not was_running else " (a turn was already running)"))
                else:
                    Client._unwrap(frame)
    finally:
        client.close()

    text_out = "".join(streamed).strip()
    result = {"device": name, "sessionId": session_id, "sent": text, "images": len(images),
              "accepted": state["accepted"], "events": collected, "text": text_out}
    if not opts["json"]:
        if text_out:
            sys.stdout.write("\n")
        sys.stdout.flush()
    return result


# --------------------------------------------------------------------------- #
# argument parsing
# --------------------------------------------------------------------------- #

def split_globals(argv):
    """Hoist --device/--json/--timeout so they may appear before or after a command."""
    opts = {"device": None, "json": False, "timeout": DEFAULT_TIMEOUT}
    rest = []
    index = 0
    while index < len(argv):
        token = argv[index]
        if token in ("-d", "--device") and index + 1 < len(argv):
            opts["device"] = argv[index + 1]
            index += 2
            continue
        if token.startswith("--device="):
            opts["device"] = token.split("=", 1)[1]
            index += 1
            continue
        if token == "--json":
            opts["json"] = True
            index += 1
            continue
        if token == "--timeout" and index + 1 < len(argv):
            opts["timeout"] = float(argv[index + 1])
            index += 2
            continue
        if token.startswith("--timeout="):
            opts["timeout"] = float(token.split("=", 1)[1])
            index += 1
            continue
        rest.append(token)
        index += 1
    if opts["timeout"] <= 0:
        raise CliError("--timeout must be positive")
    return opts, rest


def build_parser():
    parser = argparse.ArgumentParser(
        prog="dshctl",
        description="Control a DSH instance running on another device.",
        epilog="Global options (either side of the command): --device NAME, --json, --timeout SEC",
    )
    parser.add_argument("--version", action="version", version="dshctl %s" % VERSION)
    sub = parser.add_subparsers(dest="command")

    add = sub.add_parser("add", help="register a device")
    add.add_argument("name")
    add.add_argument("target", nargs="?", help="host or host:port; or use --host with --port")
    add.add_argument("--host", help="device address (devctl-style alternative to `target`)")
    add.add_argument("--port", type=int, help="control port (default %d)" % DEFAULT_PORT)
    add.add_argument("--type", default="dsh", choices=["dsh"],
                     help="device type; dsh is the only kind this CLI speaks")
    add.add_argument("--token", help="control token; defaults to $DSH_HOME/%s" % STATE_NAME)
    add.add_argument("--default", action="store_true", help="make it the default device")
    add.set_defaults(func=cmd_add)

    devices = sub.add_parser("devices", help="list registered devices", aliases=["dev"])
    devices.set_defaults(func=cmd_devices)

    rm = sub.add_parser("rm", help="forget a device", aliases=["remove"])
    rm.add_argument("name")
    rm.set_defaults(func=cmd_rm)

    use = sub.add_parser("use", help="set the default device")
    use.add_argument("name")
    use.set_defaults(func=cmd_use)

    ping = sub.add_parser("ping", help="check reachability and credentials")
    ping.set_defaults(func=cmd_ping)

    sessions = sub.add_parser("sessions", help="list Sessions", aliases=["ls", "list"])
    sessions.add_argument("-n", "--limit", type=int, default=25, help="show at most N Sessions (0 = all)")
    sessions.add_argument("--roots", action="store_true", help="hide subagent Sessions")
    sessions.set_defaults(func=cmd_sessions)

    new = sub.add_parser("new", help="create a Session")
    new.add_argument("--cwd", help="working directory on the remote device")
    new.add_argument("--workspace", help="Workspace id, id prefix, or exact title")
    new.add_argument("--preset", help="agent preset name")
    new.set_defaults(func=cmd_new)

    send = sub.add_parser("send", help="prompt a Session and stream the reply")
    send.add_argument("text", nargs="*", help="prompt text; use `-` to read stdin")
    send.add_argument("-s", "--session", help="Session id or unambiguous prefix")
    send.add_argument("--steer", action="store_true", help="steer the running turn instead of queueing")
    send.add_argument("--image", action="append", metavar="PATH",
                      help="attach an image (png/jpeg/webp/gif); repeatable")
    send.add_argument("--no-wait", dest="wait", action="store_const", const=0.0, help="return as soon as it is queued")
    send.add_argument("--wait", type=float, help="seconds to wait for the turn to finish (default 600)")
    send.add_argument("--detail", action="store_true", help="also print prompts, injections, and turn boundaries")
    send.set_defaults(func=cmd_send)

    tail = sub.add_parser("tail", help="print the latest messages of a Session")
    tail.add_argument("-s", "--session", help="Session id or unambiguous prefix")
    tail.add_argument("-n", "--number", type=int, default=40, help="how many messages (default 40)")
    tail.set_defaults(func=cmd_tail)

    watch = sub.add_parser("watch", help="stream a Session's events live")
    watch.add_argument("-s", "--session", help="Session id or unambiguous prefix")
    watch.add_argument("--for", dest="for_seconds", type=float, help="stop after this many seconds")
    watch.add_argument("--detail", action="store_true", help="include turn boundaries and unknown events")
    watch.set_defaults(func=cmd_watch)

    cancel = sub.add_parser("cancel", help="abort the running turn")
    cancel.add_argument("-s", "--session", help="Session id or unambiguous prefix")
    cancel.set_defaults(func=cmd_cancel)

    rename = sub.add_parser("rename", help="retitle a Session")
    rename.add_argument("title")
    rename.add_argument("-s", "--session", help="Session id or unambiguous prefix")
    rename.set_defaults(func=cmd_rename)

    search = sub.add_parser("search", help="search Session transcripts")
    search.add_argument("query")
    search.set_defaults(func=cmd_search)

    workspaces = sub.add_parser("workspaces", help="list Workspaces", aliases=["ws"])
    workspaces.set_defaults(func=cmd_workspaces)

    ws_new = sub.add_parser("ws-new", help="create a Workspace for a path")
    ws_new.add_argument("path", help="directory path on the remote device")
    ws_new.set_defaults(func=cmd_ws_new)

    ws_use = sub.add_parser("ws-use", help="select the Workspace new Sessions go into")
    ws_use.add_argument("workspace", help="Workspace id, id prefix, or exact title")
    ws_use.set_defaults(func=cmd_ws_use)

    ws_rename = sub.add_parser("ws-rename", help="retitle a Workspace")
    ws_rename.add_argument("workspace", help="Workspace id, id prefix, or exact title")
    ws_rename.add_argument("title")
    ws_rename.set_defaults(func=cmd_ws_rename)

    ws_rm = sub.add_parser("ws-rm", help="forget a Workspace (its Sessions and files stay)")
    ws_rm.add_argument("workspace", help="Workspace id, id prefix, or exact title")
    ws_rm.set_defaults(func=cmd_ws_rm)

    permissions = sub.add_parser("permissions", help="show or set the Session permission preset",
                                 aliases=["perm"])
    permissions.add_argument("preset", nargs="?", help="preset to apply, e.g. danger-full-access")
    permissions.add_argument("-s", "--session", help="Session id or unambiguous prefix")
    permissions.set_defaults(func=cmd_permissions)

    models = sub.add_parser("models", help="show or change the model")
    models.add_argument("--select", help="PROVIDER/MODEL to apply")
    models.add_argument("--effort", help="reasoning effort id to apply with --select")
    models.add_argument("-s", "--session", help="Session id or unambiguous prefix")
    models.set_defaults(func=cmd_models)

    return parser


def main(argv=None):
    _enable_utf8()
    argv = list(sys.argv[1:] if argv is None else argv)
    parser = build_parser()
    opts = {"device": None, "json": False, "timeout": DEFAULT_TIMEOUT}
    try:
        opts, rest = split_globals(argv)
        if not rest:
            parser.print_help()
            return 0
        args = parser.parse_args(rest)
        if not getattr(args, "func", None):
            parser.print_help()
            return 0
        config = load_config()
        result = args.func(args, config, opts)
        if opts["json"]:
            emit_json({"ok": True, "result": result})
        return 0
    except RemoteError as exc:
        if opts["json"]:
            emit_json({"ok": False, "error": {"code": exc.code, "message": exc.message}})
        else:
            sys.stderr.write("dshctl: remote error [%s] %s\n" % (exc.code, exc.message))
        return 1
    except CliError as exc:
        if opts["json"]:
            emit_json({"ok": False, "error": {"code": "cli", "message": str(exc)}})
        else:
            sys.stderr.write("dshctl: %s\n" % exc)
        return 1
    except KeyboardInterrupt:
        sys.stderr.write("\n")
        return 130
    except (socket.error, IOError, OSError) as exc:
        if opts["json"]:
            emit_json({"ok": False, "error": {"code": "io", "message": str(exc)}})
        else:
            sys.stderr.write("dshctl: %s\n" % exc)
        return 1


if __name__ == "__main__":
    sys.exit(main())
