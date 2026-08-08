#!/usr/bin/env node
import http from "node:http";

const listenPort = Number.parseInt(process.env.CLOUDFLARED_PROXY_PORT || "3310", 10);
const feTarget = process.env.CLOUDFLARED_FE_TARGET || "http://127.0.0.1:3010";
const mediaTarget = process.env.CLOUDFLARED_MEDIA_TARGET || "http://127.0.0.1:9010";
const mediaPrefix = process.env.CLOUDFLARED_MEDIA_PREFIX || "/shresta-local-assets/";

function targetForPath(pathname) {
  if (pathname.startsWith(mediaPrefix)) {
    return mediaTarget;
  }
  return feTarget;
}

const server = http.createServer((req, res) => {
  const reqUrl = new URL(req.url || "/", "http://127.0.0.1");
  const base = targetForPath(reqUrl.pathname);
  const upstreamUrl = new URL(reqUrl.pathname + reqUrl.search, base);

  const headers = { ...req.headers };
  headers.host = upstreamUrl.host;
  headers["x-forwarded-proto"] = "https";
  headers["x-forwarded-host"] = req.headers.host || "";

  const upstreamReq = http.request(
    upstreamUrl,
    {
      method: req.method,
      headers,
    },
    (upstreamRes) => {
      res.writeHead(upstreamRes.statusCode || 502, upstreamRes.headers);
      upstreamRes.pipe(res);
    },
  );

  upstreamReq.on("error", (error) => {
    res.writeHead(502, { "content-type": "text/plain" });
    res.end(`Proxy upstream error: ${error.message}`);
  });

  req.pipe(upstreamReq);
});

server.listen(listenPort, "127.0.0.1", () => {
  console.log(
    `[cloudflared-proxy] listening on http://127.0.0.1:${listenPort} (fe=${feTarget}, media=${mediaTarget}, mediaPrefix=${mediaPrefix})`,
  );
});
