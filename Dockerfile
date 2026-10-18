FROM node:22-alpine
WORKDIR /app
COPY shared shared
COPY server server
COPY package.json .
VOLUME /data
EXPOSE 8080 8081/udp
CMD ["node", "server/server.js", "--http", "8080", "--udp", "8081", "--data", "/data/minilan-data.json"]
