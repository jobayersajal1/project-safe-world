import { resolve } from "node:path";
import { defineConfig } from "vite";
import { crx } from "@crxjs/vite-plugin";
import manifest from "./manifest.json" with { type: "json" };

export default defineConfig({
  plugins: [crx({ manifest })],
  build: {
    outDir: "dist",
    emptyOutDir: true,
    rollupOptions: {
      input: {
        // The offscreen document, which runs the blur detection models.
        //
        // It has to be named here because crxjs only treats an HTML file as an
        // entry when the manifest points at it — `action.default_popup`,
        // `options_page`, and so on. There is no manifest key for an offscreen
        // page (it is created at runtime by `chrome.offscreen.createDocument`),
        // and listing it under `web_accessible_resources` gets it *copied*
        // rather than built: the `<script src="./detect.ts">` survives verbatim
        // into `dist` and 404s. Declaring it as an input makes vite compile it
        // like any other page, at the same path the runtime asks for.
        offscreen: resolve(import.meta.dirname, "src/offscreen/offscreen.html"),
      },
    },
  },
  server: {
    port: 5173,
    strictPort: true,
    hmr: { port: 5173 },
  },
});
