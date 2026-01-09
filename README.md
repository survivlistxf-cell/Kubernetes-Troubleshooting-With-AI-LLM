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

## 🤖 AI routing (Backend → AI Server → Ollama)

UI-ul continuă să trimită mesajele la backend-ul existent pe `http://localhost:8080/api/chat`.

Backend-ul (8080) **forward-ează** prompt-ul către AI Server (8090) la `POST http://localhost:8090/v1/chat` folosind payload `kdiag/1.0`.
Dacă AI Server nu e pornit / nu răspunde, backend-ul revine la răspunsul „legacy” (heuristic).

### Config

Backend: `backend/src/main/resources/application.properties`
- `ai.server.base-url=http://localhost:8090`

AI Server: `Server/src/main/resources/application.properties`
- `ollama.base-url=http://localhost:11434`
- `ollama.model=llama3.1`

AI Server folosește endpoint-ul OpenAI-compatible din Ollama: `POST /v1/chat/completions`.
Dacă la tine Ollama nu are acest endpoint (ci doar `/api/chat`), spune-mi și îl ajustez.

## 📚 Resurse Utile

- [Spring Boot Documentation](https://spring.io/projects/spring-boot)
- [Express.js Documentation](https://expressjs.com/)
- [Maven Documentation](https://maven.apache.org/)
- [npm Documentation](https://docs.npmjs.com/)
