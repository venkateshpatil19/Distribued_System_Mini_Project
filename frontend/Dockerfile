# Builds the Next.js dashboard and runs its standalone server.
FROM node:20-slim AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM node:20-slim
WORKDIR /app
# HOSTNAME must be 0.0.0.0: Docker sets it to the container id, and Next binds to it.
ENV NODE_ENV=production PORT=3000 HOSTNAME=0.0.0.0
COPY --from=build /src/.next/standalone ./
EXPOSE 3000
CMD ["node", "server.js"]
