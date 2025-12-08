# Ghid Docker pentru Aplicația Spring Boot + Node.js

## 📋 Cerințe
- Docker Desktop (pe Windows/Mac) sau Docker Engine + Docker Compose (pe Linux)
- Git pentru a clona/transfera codul

## 🚀 Comenzi Rapide

### Rulare completă (backend + frontend)
```bash
docker-compose up --build
```

### Oprire
```bash
docker-compose down
```

### Rebuild fără cache
```bash
docker-compose up --build --no-cache
```

---

## 📦 Structura Docker

### Backend (Spring Boot)
- **Dockerfile**: `backend/Dockerfile`
- **Port**: 8080
- **Imagine de bază**: `eclipse-temurin:17-jre-alpine` (ușoară, securizată)
- **Build în 2 stagii**: Maven compileaza, JRE rulează

### Frontend (Node.js)
- **Dockerfile**: `frontend/Dockerfile`
- **Port**: 3000
- **Imagine de bază**: `node:20-alpine` (ușoară)
- **Build în 2 stagii**: Builder face install, runtime minimal

---

## 🔗 Networking

Containerele comunică prin rețea `proiect-network`:
- Frontend poate accesa backend la `http://backend:8080`
- Backend la `http://backend:8080`
- Frontend la `http://frontend:3000`

---

## 🔧 Configurare pe Mașina Virtuală Linux

### 1. Instalează Docker
```bash
# Ubuntu/Debian
sudo apt update
sudo apt install docker.io docker-compose

# Sau folosește script oficial
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh
```

### 2. Adaugă utilizatorul la grupul docker
```bash
sudo usermod -aG docker $USER
newgrp docker
```

### 3. Clonează/Copiază codul
```bash
git clone <repository-url>
cd Proiect
# sau transfer prin SCP, USB, etc.
```

### 4. Rulează aplicația
```bash
docker-compose up --build
```

### 5. Acces
- **Frontend**: `http://localhost:3000`
- **Backend**: `http://localhost:8080`

---

## 📊 Comenzi Utile

### Vezi containerele active
```bash
docker-compose ps
```

### Vezi logurile
```bash
# Toate
docker-compose logs -f

# Doar backend
docker-compose logs -f backend

# Doar frontend
docker-compose logs -f frontend
```

### Accesează containerul
```bash
# Backend
docker-compose exec backend sh

# Frontend
docker-compose exec frontend sh
```

### Șterge tot (inclusiv imagini)
```bash
docker-compose down -v
docker system prune -a
```

---

## ⚙️ Variabile de Mediu

### Backend
- `SPRING_PROFILES_ACTIVE`: Profil Spring (default: `docker`)
- `JAVA_OPTS`: Opțiuni JVM (memorie, etc.)

### Frontend
- `NODE_ENV`: production/development
- `REACT_APP_API_URL`: URL-ul backend-ului

Editează `docker-compose.yml` pentru a le modifica.

---

## 🐛 Troubleshooting

### Porturile sunt ocupate
```bash
# Schimbă porturile în docker-compose.yml
# De ex: "3001:3000" în loc de "3000:3000"
```

### Backend nu se conectează
- Verifică că numele service-ului din `docker-compose.yml` este `backend`
- Frontend trebuie să use `http://backend:8080` (nu localhost)

### Container se oprește imediat
```bash
docker-compose logs backend
docker-compose logs frontend
```

### Memorie insuficientă
- Modifică `JAVA_OPTS` în `docker-compose.yml`
- Reduce alți servicii pe mașina virtuală

---

## 🎯 Dezvoltare Locală vs Docker

### Pe mașina ta (Windows):
```bash
# Terminal 1 - Backend
cd backend
mvn spring-boot:run

# Terminal 2 - Frontend
cd frontend
npm install
npm start
```

### Pe mașina virtuală Linux (Docker):
```bash
docker-compose up --build
```

---

## 📝 Modificări Future

Dacă schimbi codul:
1. Commit & push pe git
2. Pe VM: `git pull`
3. Rulează: `docker-compose up --build`

---

Gata! 🚀 Aplicația ta rulează pe Docker! 🐳
