// Serves app/ on this computer so a desktop Chromium can run the page:
//   node serve.js            then open http://localhost:8572/
// To reach it from another device on the home network (a TV's own browser), say so:
//   HOST=0.0.0.0 node serve.js
// A page opened as http://localhost:8572/?report also posts its results here, and they are printed:
// that is how a run nobody watches (a headless browser) hands its numbers back.
const http = require("http");
const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "app");
const port = Number(process.env.PORT || 8572);
const types = { ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8", ".png": "image/png" };

http.createServer((request, response) => {
  if (request.method === "POST" && request.url === "/report") {
    let body = "";
    request.on("data", (chunk) => { body += chunk; });
    request.on("end", () => {
      console.log(body);
      if (process.env.REPORT_FILE) fs.appendFileSync(process.env.REPORT_FILE, body + "\n");
      response.end();
    });
    return;
  }
  const name = decodeURIComponent(request.url.split("?")[0]);
  const file = path.join(root, name === "/" ? "index.html" : name);
  if (!file.startsWith(root) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    response.writeHead(404);
    response.end();
    return;
  }
  response.writeHead(200, { "Content-Type": types[path.extname(file)] || "application/octet-stream", "Cache-Control": "no-store" });
  fs.createReadStream(file).pipe(response);
}).listen(port, process.env.HOST || "127.0.0.1", () => console.log("http://localhost:" + port + "/"));
