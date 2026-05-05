# Proiect Spring Boot + Node.js

Proiect hibrid cu backend în Spring Boot și frontend/server în Node.js.

## 📁 Structura Proiectului

```
Proiect/
├── springboot-backend/       # Backend Java cu Spring Boot
│   ├── pom.xml              # Maven configuration
│   ├── src/
│   │   └── main/
│   │       ├── java/com/example/
│   │       │   └── Application.java
│   │       └── resources/
│   │           └── application.properties
│   └── .gitignore
│
├── nodejs-frontend/          # Frontend Node.js cu Express
│   ├── package.json
│   ├── server.js
│   ├── node_modules/         # (se instalează cu npm install)
│   └── .gitignore
│
└── README.md                 # Acest fișier
```

## 🚀 Instrucțiuni de Instalare

### Backend (Spring Boot)

**Cerințe:**
- Java 17+ instalat
- Maven instalat

**Instalare:**
```bash
cd springboot-backend
mvn clean install
```

**Pornire:**
```bash
mvn spring-boot:run
```

Backend va rula pe: `http://localhost:8080`

**Endpoint-uri disponibile:**
- `GET /api/hello` - Test endpoint
- `GET /api/status` - Status backend
- `GET /h2-console` - H2 Database console

---

### Frontend (Node.js)

**Cerințe:**
- Node.js 16+ instalat
- npm

**Instalare:**
```bash
cd nodejs-frontend
npm install
```

**Pornire:**
```bash
npm start
```

**Sau cu reîncărcare automată:**
```bash
npm run dev
```

Frontend va rula pe: `http://localhost:3000`

**Endpoint-uri disponibile:**
- `GET /` - Informații server
- `GET /api/hello` - Proxy la backend
- `GET /api/status` - Status complet
- `GET /health` - Health check

---

## 🔧 Configurare Port-uri

- **Spring Boot Backend**: `:8080` (configurabil în `application.properties`)
- **Node.js Frontend**: `:3000` (configurabil în `server.js`)

## 📝 Note

- Comunicația între Node.js și Spring Boot se face prin HTTP REST
- CORS este activat în ambele aplicații
- Database: H2 în-memorie (pentru development)

## ☸️ Kubernetes/OpenStack deploy

Pentru OpenStack, baza de deploy este gândită să ruleze într-un namespace separat, de exemplu `kdiag`.
Frontend-ul este expus prin `NodePort 30082`, iar Octavia poate trimite traficul din floating IP-ul `203.25.143.78` către nodePort-ul respectiv.

Arhitectura din manifestele K8s este:

- `frontend` public
- `backend` intern
- `postgres` comun pentru backend

Fișierele de deploy sunt în [k8s](k8s).

## 📚 Resurse Utile

- [Spring Boot Documentation](https://spring.io/projects/spring-boot)
- [Express.js Documentation](https://expressjs.com/)
- [Maven Documentation](https://maven.apache.org/)
- [npm Documentation](https://docs.npmjs.com/)
