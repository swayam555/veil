# Build context is the repository root (see docker-compose.yml), so the browser extension can be packaged
# into the site as a download.
FROM node:24-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
COPY extension /extension
RUN apk add --no-cache zip \
    && mkdir -p public/downloads \
    && cd / && zip -qr /app/public/downloads/veil-extension.zip extension -x 'extension/test/*' 'extension/package.json' 'extension/node_modules/*' \
    && cd /app && npx ng build --configuration production

FROM nginx:1.30-alpine
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
EXPOSE 80
