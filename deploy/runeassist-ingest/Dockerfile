FROM node:22-alpine
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install --omit=dev
COPY ingest-server.mjs flip-scorer.mjs flip-ledger.mjs account-sync.mjs ./
COPY website-dist ./website-dist
ENV PORT=8790 DATA_DIR=/data WEBSITE_DIR=/app/website-dist
EXPOSE 8790
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8790/health || exit 1
CMD ["node", "ingest-server.mjs"]
