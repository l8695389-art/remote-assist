import { Room, Env as RoomEnv } from "./room";

export { Room };

export interface Env extends RoomEnv {}

function randomNumericCode(length = 6): string {
  const digits = new Uint32Array(length);
  crypto.getRandomValues(digits);
  return Array.from(digits, (d) => (d % 10).toString()).join("");
}

/**
 * Only two routes:
 *   GET /signal                 (host, first message must be {type:"create_room"})
 *   GET /signal?code=XXXXXX     (controller, first message must be {type:"join"})
 *
 * The Worker itself never inspects WebRTC SDP/ICE payloads — it purely upgrades the
 * connection and hands it off to the Durable Object that owns that pairing code, which
 * relays messages between exactly the two sockets in that room. See src/room.ts.
 */
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname !== "/signal") {
      return new Response("not found", { status: 404 });
    }
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("expected websocket upgrade", { status: 426 });
    }

    const existingCode = url.searchParams.get("code");

    if (existingCode) {
      // Controller joining an existing room.
      if (!/^\d{6,8}$/.test(existingCode)) {
        return new Response("invalid code format", { status: 400 });
      }
      const id = env.ROOM.idFromName(existingCode);
      const stub = env.ROOM.get(id);
      return stub.fetch(request);
    }

    // Host requesting a brand-new room. A random 6-digit code is picked and forwarded
    // to the Room DO that owns it. If that DO already has an active room (collision —
    // rare given the 5-minute code lifetime and 6-digit space), it replies over the
    // socket with {type:"error", reason:"room_already_active"}; the Android client's
    // create_room path treats that as "reconnect and try again". For higher-volume,
    // multi-tenant deployments, front this with a separate Registry Durable Object
    // that reserves codes atomically before opening the WebSocket — see README.
    const code = randomNumericCode(6);
    const id = env.ROOM.idFromName(code);
    const stub = env.ROOM.get(id);
    return stub.fetch(request);
  },
};
