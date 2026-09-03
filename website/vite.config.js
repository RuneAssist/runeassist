import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  base: '/app/',
  // A CDN (Cloudflare) sits in front of this site and long-caches /assets/* by content-hashed
  // filename. If a bad deploy ever gets cached under a given hash, re-uploading the *same*
  // content later keeps that hash and the CDN keeps serving the stale bad response -- this
  // define forces every build's JS to differ by at least this timestamp, so a re-deploy always
  // gets a filename the CDN has never seen, regardless of whether the app code itself changed.
  define: {
    __BUILD_TIME__: JSON.stringify(Date.now()),
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
});
