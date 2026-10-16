FROM mcr.microsoft.com/playwright:v1.63.0-noble
WORKDIR /app
COPY engine/package*.json ./engine/
RUN cd engine && npm ci --omit=dev
COPY engine ./engine
ENV NODE_ENV=production
EXPOSE 10000
CMD ["node", "engine/server.js"]
