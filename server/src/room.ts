export interface Env {
  ROOM: DurableObjectNamespace;
}

const PAIRING_TTL_MS = 5 * 60 * 1000; // 5 minutes to be claimed by a controller
const MAX_MSGS_PER_WINDOW = 40;       // basic rate limit
const RATE_WINDOW_MS = 1000;

type Role = "host" | "controller";

interface SocketAttachment {
  role: Role;
  lastSeq: number;
  windowStart: number;
  windowCount: number;
}

/**
 * One Room = one pairing code = at most one host + one controller.
 *
 * Lifecycle:
 *  - host connects, sends {type:"create_room"} -> room stores host socket, generates
 *    a sessionId, replies {type:"room_created", code, sessionId, expiresInSeconds}.
 *  - an alarm is scheduled for PAIRING_TTL_MS; if no controller has joined by then,
 *    the room is closed and destroyed (pairing code expires).
 *  - controller connects with the code, sends {type:"join", code} -> room validates
 *    the code is active + unclaimed, stores controller socket, replies {type:"joined"}
 *    to the controller and {type:"peer_joined"} to the host, and cancels the TTL alarm.
 *  - offer / answer / ice_candidate messages are relayed verbatim to the other party.
 *  - either side can send {type:"cancel_room"} or {type:"peer_left"}, or simply
 *    disconnect; the room then notifies the remaining party and destroys itself.
 *
 * Every message is checked for monotonic seq (replay/out-of-order defense) and rate
 * limited per socket.
 */
export class Room implements DurableObject {
  state: DurableObjectState;
  env: Env;
  code: string | null = null;
  sessionId: string | null = null;
  hostWs: WebSocket | null = null;
  controllerWs: WebSocket | null = null;

  constructor(state: DurableObjectState, env: Env) {
    this.state = state;
    this.env = env;
  }

  async fetch(request: Request): Promise<Response> {
    const upgrade = request.headers.get("Upgrade");
    if (upgrade !== "websocket") {
      return new Response("expected websocket", { status: 426 });
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);

    // Hibernatable WebSocket API: the DO can be evicted from memory between
    // messages and woken back up; socket state is restored via serializeAttachment.
    this.state.acceptWebSocket(server);

    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, raw: string | ArrayBuffer) {
    let msg: any;
    try {
      msg = JSON.parse(typeof raw === "string" ? raw : new TextDecoder().decode(raw));
    } catch {
      this.safeSend(ws, { type: "error", reason: "invalid_json" });
      return;
    }

    if (!this.checkRateAndSeq(ws, msg)) {
      this.safeSend(ws, { type: "error", reason: "rate_limited_or_replay" });
      return;
    }

    switch (msg.type) {
      case "create_room":
        await this.handleCreateRoom(ws);
        break;
      case "join":
        await this.handleJoin(ws, msg);
        break;
      case "offer":
      case "answer":
      case "ice_candidate":
        this.relay(ws, msg);
        break;
      case "cancel_room":
        await this.handleLeave(ws, "cancel_room");
        break;
      case "peer_left":
        await this.handleLeave(ws, "peer_left");
        break;
      default:
        this.safeSend(ws, { type: "error", reason: "unknown_type" });
    }
  }

  async webSocketClose(ws: WebSocket) {
    await this.handleLeave(ws, "peer_left");
  }

  async webSocketError(ws: WebSocket) {
    await this.handleLeave(ws, "peer_left");
  }

  /** Fired PAIRING_TTL_MS after room creation if no controller ever joined. */
  async alarm() {
    if (!this.controllerWs && this.hostWs) {
      this.safeSend(this.hostWs, { type: "error", reason: "code_expired" });
      try { this.hostWs.close(4000, "code_expired"); } catch {}
    }
    this.hostWs = null;
    this.controllerWs = null;
    this.code = null;
    this.sessionId = null;
  }

  // ---------------- handlers ----------------

  private async handleCreateRoom(ws: WebSocket) {
    if (this.hostWs) {
      this.safeSend(ws, { type: "error", reason: "room_already_active" });
      return;
    }
    this.hostWs = ws;
    this.code = this.state.id.name ?? this.state.id.toString().slice(0, 8);
    this.sessionId = crypto.randomUUID();
    ws.serializeAttachment({ role: "host", lastSeq: 0, windowStart: Date.now(), windowCount: 0 } as SocketAttachment);

    await this.state.storage.setAlarm(Date.now() + PAIRING_TTL_MS);

    this.safeSend(ws, {
      type: "room_created",
      code: this.code,
      sessionId: this.sessionId,
      expiresInSeconds: PAIRING_TTL_MS / 1000,
    });
  }

  private async handleJoin(ws: WebSocket, msg: any) {
    if (!this.hostWs || !this.code) {
      this.safeSend(ws, { type: "error", reason: "room_not_found" });
      ws.close(4004, "room_not_found");
      return;
    }
    if (msg.code !== this.code) {
      this.safeSend(ws, { type: "error", reason: "invalid_code" });
      ws.close(4001, "invalid_code");
      return;
    }
    if (this.controllerWs) {
      this.safeSend(ws, { type: "error", reason: "room_full" });
      ws.close(4002, "room_full");
      return;
    }

    this.controllerWs = ws;
    ws.serializeAttachment({ role: "controller", lastSeq: 0, windowStart: Date.now(), windowCount: 0 } as SocketAttachment);

    // Room is now paired; cancel the "unclaimed code" TTL.
    await this.state.storage.deleteAlarm();

    this.safeSend(ws, { type: "joined", code: this.code, sessionId: this.sessionId });
    this.safeSend(this.hostWs, { type: "peer_joined", sessionId: this.sessionId });
  }

  private relay(fromWs: WebSocket, msg: any) {
    const target = fromWs === this.hostWs ? this.controllerWs : this.hostWs;
    if (!target) {
      this.safeSend(fromWs, { type: "error", reason: "peer_not_connected" });
      return;
    }
    if (this.sessionId && msg.sessionId && msg.sessionId !== this.sessionId) {
      this.safeSend(fromWs, { type: "error", reason: "session_mismatch" });
      return;
    }
    this.safeSend(target, msg);
  }

  private async handleLeave(ws: WebSocket, reasonType: string) {
    const isHost = ws === this.hostWs;
    const isController = ws === this.controllerWs;
    if (!isHost && !isController) return;

    const other = isHost ? this.controllerWs : this.hostWs;
    if (other) {
      this.safeSend(other, { type: "peer_left", reason: reasonType });
      try { other.close(4003, reasonType); } catch {}
    }
    try { ws.close(1000, reasonType); } catch {}

    this.hostWs = null;
    this.controllerWs = null;
    this.code = null;
    this.sessionId = null;
    await this.state.storage.deleteAlarm();
  }

  private checkRateAndSeq(ws: WebSocket, msg: any): boolean {
    let attach = ws.deserializeAttachment() as SocketAttachment | null;
    const now = Date.now();
    if (!attach) {
      attach = { role: ws === this.hostWs ? "host" : "controller", lastSeq: 0, windowStart: now, windowCount: 0 };
    }

    // sliding window rate limit
    if (now - attach.windowStart > RATE_WINDOW_MS) {
      attach.windowStart = now;
      attach.windowCount = 0;
    }
    attach.windowCount += 1;
    if (attach.windowCount > MAX_MSGS_PER_WINDOW) {
      ws.serializeAttachment(attach);
      return false;
    }

    // monotonic sequence check (replay / out-of-order defense)
    const seq = typeof msg.seq === "number" ? msg.seq : 0;
    if (seq !== 0 && seq <= attach.lastSeq) {
      ws.serializeAttachment(attach);
      return false;
    }
    if (seq !== 0) attach.lastSeq = seq;

    ws.serializeAttachment(attach);
    return true;
  }

  private safeSend(ws: WebSocket, obj: unknown) {
    try {
      ws.send(JSON.stringify(obj));
    } catch {
      // socket already closed; ignore
    }
  }
}
