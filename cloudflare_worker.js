/**
 * Royal TV / Prime OTT - Full Cloud Storage Worker (Cloudflare KV / D1 / Memory)
 * Stores Users, Google OAuth Accounts, Per-Email Profiles, Settings, and Active Watch Rooms.
 */

const memUsersByEmail = new Map();
const memUsersByUsername = new Map();
const memRooms = new Map();
const memRoomEvents = new Map();
const memVoiceChunks = new Map();

async function getUserFromCloud(env, key) {
  const cleanKey = (key || "").toLowerCase().trim();
  if (!cleanKey) return null;
  if (env && env.ROYAL_KV) {
    const raw = await env.ROYAL_KV.get("user:" + cleanKey);
    if (raw) return JSON.parse(raw);
  }
  return memUsersByEmail.get(cleanKey) || memUsersByUsername.get(cleanKey) || null;
}

async function saveUserToCloud(env, userObj) {
  const email = (userObj.email || "").toLowerCase().trim();
  const username = (userObj.username || "").toLowerCase().trim();
  if (email) memUsersByEmail.set(email, userObj);
  if (username) memUsersByUsername.set(username, userObj);
  if (env && env.ROYAL_KV) {
    const raw = JSON.stringify(userObj);
    if (email) await env.ROYAL_KV.put("user:" + email, raw);
    if (username) await env.ROYAL_KV.put("user:" + username, raw);
  }
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname;
    const corsHeaders = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, Authorization, X-User-Email, X-User-Id",
    };

    if (request.method === "OPTIONS") {
      return new Response(null, { headers: corsHeaders });
    }

    const jsonResp = (obj, status = 200) =>
      new Response(JSON.stringify(obj), {
        status,
        headers: { ...corsHeaders, "Content-Type": "application/json" }
      });

    // 1. Health Check
    if (path === "/health") {
      return jsonResp({ status: "OK", timestamp: Date.now() });
    }

    // 2. Register New Account on Cloudflare
    if (path === "/auth/register" && request.method === "POST") {
      try {
        const body = await request.json();
        const email = (body.email || "").toLowerCase().trim();
        const username = (body.username || "").trim();
        const password = body.password || "";
        if (!email || !username || !password) {
          return jsonResp({ success: false, message: "Missing required fields" }, 400);
        }
        const existingEmail = await getUserFromCloud(env, email);
        if (existingEmail && existingEmail.hasPassword) {
          return jsonResp({ success: false, message: "Email Already Registered" }, 409);
        }
        const existingUser = await getUserFromCloud(env, username);
        if (existingUser && existingUser.email !== email) {
          return jsonResp({ success: false, message: "Username Already Exists" }, 409);
        }

        const userRecord = {
          email,
          username,
          fullName: body.fullName || username,
          password,
          hasPassword: true,
          avatar: body.avatar || "👑",
          photoUri: body.photoUri || "",
          prefQualityIdx: body.prefQualityIdx ?? 0,
          notifEnabled: body.notifEnabled ?? true,
          voiceClear: body.voiceClear ?? true,
          autoAudio: body.autoAudio ?? true,
          appLang: body.appLang || "ar",
          updatedAt: Date.now()
        };
        await saveUserToCloud(env, userRecord);
        return jsonResp({ success: true, user: userRecord });
      } catch (e) {
        return jsonResp({ success: false, message: e.message }, 400);
      }
    }

    // 3. Login with Username or Email + Password from Cloudflare
    if (path === "/auth/login" && request.method === "POST") {
      try {
        const body = await request.json();
        const identifier = (body.username || body.email || "").toLowerCase().trim();
        const password = body.password || "";
        const user = await getUserFromCloud(env, identifier);

        if (!user) {
          return jsonResp({ success: false, errorType: "NOT_FOUND", message: "Account not found" }, 404);
        }
        if (!user.hasPassword || user.password === "GOOGLE_OAUTH_ONLY") {
          return jsonResp({ success: false, errorType: "GOOGLE_NO_PASS", message: "Google account has no password set yet" }, 403);
        }
        if (user.password !== password) {
          return jsonResp({ success: false, errorType: "WRONG_PASS", message: "Invalid password" }, 401);
        }
        return jsonResp({ success: true, user });
      } catch (e) {
        return jsonResp({ success: false, message: e.message }, 400);
      }
    }

    // 4. Google OAuth Login & Cloud Sync
    if (path === "/auth/google" && request.method === "POST") {
      try {
        const body = await request.json();
        const email = (body.email || "").toLowerCase().trim();
        if (!email) return jsonResp({ success: false, message: "Email required" }, 400);

        let user = await getUserFromCloud(env, email);
        if (!user) {
          let baseUsername = email.split("@")[0];
          let existingByU = await getUserFromCloud(env, baseUsername);
          if (existingByU && existingByU.email !== email) {
            baseUsername = baseUsername + "_" + Math.floor(100 + Math.random() * 900);
          }
          user = {
            email,
            username: baseUsername,
            fullName: baseUsername,
            password: "GOOGLE_OAUTH_ONLY",
            hasPassword: false,
            oauthToken: body.oauthToken || "GOOGLE_AUTHORIZED",
            avatar: "👑",
            photoUri: "",
            prefQualityIdx: 0,
            notifEnabled: true,
            voiceClear: true,
            autoAudio: true,
            appLang: "ar",
            updatedAt: Date.now()
          };
          await saveUserToCloud(env, user);
        }
        return jsonResp({ success: true, user });
      } catch (e) {
        return jsonResp({ success: false, message: e.message }, 400);
      }
    }

    // 5. Get or Update User Profile & Settings on Cloudflare (Isolated per Email)
    if (path === "/user/profile" && request.method === "GET") {
      const email = (url.searchParams.get("email") || "").toLowerCase().trim();
      const user = await getUserFromCloud(env, email);
      if (!user) return jsonResp({ success: false, message: "User not found" }, 404);
      return jsonResp({ success: true, user });
    }

    if (path === "/user/profile" && request.method === "POST") {
      try {
        const body = await request.json();
        const email = (body.email || "").toLowerCase().trim();
        if (!email) return jsonResp({ success: false, message: "Email required" }, 400);

        let user = await getUserFromCloud(env, email);
        if (!user) {
          user = { email, username: body.username || email.split("@")[0], hasPassword: false };
        }

        if (body.username && body.username !== user.username) {
          const checkTaken = await getUserFromCloud(env, body.username);
          if (checkTaken && checkTaken.email !== email) {
            return jsonResp({ success: false, message: "Username already taken" }, 409);
          }
          memUsersByUsername.delete((user.username || "").toLowerCase());
          user.username = body.username;
          user.fullName = body.username;
        }

        if (body.password && body.password !== "GOOGLE_OAUTH_ONLY") {
          user.password = body.password;
          user.hasPassword = true;
        }
        if (body.avatar !== undefined) user.avatar = body.avatar;
        if (body.photoUri !== undefined) user.photoUri = body.photoUri;
        if (body.prefQualityIdx !== undefined) user.prefQualityIdx = body.prefQualityIdx;
        if (body.notifEnabled !== undefined) user.notifEnabled = body.notifEnabled;
        if (body.voiceClear !== undefined) user.voiceClear = body.voiceClear;
        if (body.autoAudio !== undefined) user.autoAudio = body.autoAudio;
        if (body.appLang !== undefined) user.appLang = body.appLang;
        user.updatedAt = Date.now();

        await saveUserToCloud(env, user);
        return jsonResp({ success: true, user });
      } catch (e) {
        return jsonResp({ success: false, message: e.message }, 400);
      }
    }

    // 6. Watch Together Rooms (Public & Active Only, Auto-Close Inactive)
    if (path === "/rooms/create" && request.method === "POST") {
      try {
        const room = await request.json();
        room.createdAt = Date.now();
        room.lastActiveAt = Date.now();
        room.expiresAt = Date.now() + 3 * 3600 * 1000;
        room.onlineCount = Math.max(1, room.onlineCount || 1);
        room.status = "LIVE";
        memRooms.set(room.roomId, room);
        return jsonResp({ success: true, room });
      } catch (e) {
        return jsonResp({ success: false, message: e.message }, 400);
      }
    }

    if (path === "/rooms/list" && request.method === "GET") {
      const now = Date.now();
      const rooms = [];
      for (const [id, r] of memRooms.entries()) {
        const inactiveTooLong = (now - (r.lastActiveAt || r.createdAt || now)) > 120000;
        if (r.expiresAt > now && r.status !== "CLOSED" && (r.onlineCount || 0) > 0 && !inactiveTooLong) {
          rooms.push(r);
        } else {
          memRooms.delete(id);
        }
      }
      return jsonResp({ success: true, rooms });
    }

    if (path === "/room/event" && request.method === "POST") {
      try {
        const ev = await request.json();
        const roomId = ev.roomId;
        if (ev.type === "ROOM_CLOSED" || ev.status === "CLOSED" || ev.onlineCount === 0) {
          memRooms.delete(roomId);
          return jsonResp({ success: true, closed: true });
        }
        if (memRooms.has(roomId)) {
          const r = memRooms.get(roomId);
          r.lastActiveAt = Date.now();
          if (ev.onlineCount !== undefined) r.onlineCount = ev.onlineCount;
          if (ev.type === "PLAYBACK_SYNC" && ev.data) {
            r.streamUrl = ev.data.streamUrl || r.streamUrl;
            r.streamTitle = ev.data.streamTitle || r.streamTitle;
            r.isPlaying = ev.data.isPlaying ?? r.isPlaying;
            r.playbackPositionMs = ev.data.positionMs ?? r.playbackPositionMs;
          }
        }
        if (!memRoomEvents.has(roomId)) memRoomEvents.set(roomId, []);
        const list = memRoomEvents.get(roomId);
        list.push(ev);
        if (list.length > 50) list.shift();
        return jsonResp({ success: true });
      } catch (e) {
        return jsonResp({ success: false, message: e.message }, 400);
      }
    }

    if (path === "/voice/push" && request.method === "POST") {
      try {
        const chunk = await request.json();
        const roomId = chunk.roomId;
        if (!memVoiceChunks.has(roomId)) memVoiceChunks.set(roomId, []);
        const arr = memVoiceChunks.get(roomId);
        arr.push(chunk);
        if (arr.length > 30) arr.shift();
        return jsonResp({ success: true });
      } catch (e) {
        return jsonResp({ success: false }, 400);
      }
    }

    if (path === "/voice/pull" && request.method === "GET") {
      const roomId = url.searchParams.get("roomId");
      const since = parseInt(url.searchParams.get("since") || "0", 10);
      const arr = memVoiceChunks.get(roomId) || [];
      return jsonResp({ chunks: arr.filter(c => c.ts > since) });
    }

    if (path === "/ws/room") {
      const upgradeHeader = request.headers.get("Upgrade");
      if (!upgradeHeader || upgradeHeader !== "websocket") {
        return new Response("Expected WebSocket", { status: 426 });
      }
      const pair = new WebSocketPair();
      const [client, server] = Object.values(pair);
      server.accept();
      server.addEventListener("message", event => {
        try { server.send(event.data); } catch (_) {}
      });
      return new Response(null, { status: 101, webSocket: client });
    }

    return new Response("Royal TV Cloud Core Active", { status: 200, headers: corsHeaders });
  }
};