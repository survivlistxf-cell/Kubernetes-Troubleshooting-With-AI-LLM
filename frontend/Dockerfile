# Build stage
FROM node:20-alpine AS builder

WORKDIR /app

COPY package*.json ./
RUN npm ci --only=production

# Runtime stage
FROM node:20-alpine

WORKDIR /app

# Copy node_modules din builder
COPY --from=builder /app/node_modules ./node_modules

# Copy aplicatia
COPY . .

# Expose port (schimba 3000 daca aplicatia ta foloseste alt port)
EXPOSE 3000

# Run aplicatia
CMD ["node", "server.js"]
