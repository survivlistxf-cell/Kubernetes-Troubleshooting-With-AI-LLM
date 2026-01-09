# 🚀 Kubexplain - PostgreSQL Integration & Authentication Setup

## 📋 Ce a fost adăugat

### 1. **Frontend - UI pentru Login/Register**
- ✅ Modal pentru Login (email + password)
- ✅ Modal pentru Register (username + email + password)
- ✅ Butoane styled în tema aplicației
- ✅ Stocare token și username în localStorage
- ✅ Afișare username în sidebar după login
- ✅ Logout functionality
- ✅ Mesaje de error/success

### 2. **Backend - Autentificare & Database**
- ✅ **User Entity** - Entitate JPA cu email, username, password
- ✅ **Chat Entity** - Entitate JPA cu relationship ManyToOne la User
- ✅ **UserRepository** - Spring Data JPA repository
- ✅ **ChatRepository** - Spring Data JPA repository
- ✅ **AuthController** - Endpoints pentru /api/auth/login și /api/auth/register
- ✅ **ChatController** - Endpoints pentru salvare și preluare chat history
- ✅ **SecurityConfig** - BCrypt password encryption

### 3. **Database - PostgreSQL**
- ✅ Tabela `users` cu username, email, password encrypted
- ✅ Tabela `chats` cu relationship la users
- ✅ Indexes pentru performance
- ✅ Timestamps pentru tracking

### 4. **Docker**
- ✅ PostgreSQL container în docker-compose.yml
- ✅ Volume persistent pentru date
- ✅ Health checks
- ✅ Database initialization script (init.sql)

---

## ⚙️ Setup Instructions

### **Opțiunea 1: Rulare cu Docker (Recomandat)**

```bash
cd c:\Users\axine\Desktop\Licenta\Proiect

# Build și start containerele
docker-compose up -d

# Verificare că serviciile sunt running
docker-compose ps

# Vizionare logs
docker-compose logs -f backend
docker-compose logs -f postgres
```

**Acces:**
- Frontend: http://localhost:3000
- Backend API: http://localhost:8080
- PostgreSQL (via pgAdmin): localhost:5432

---

### **Opțiunea 2: Rulare Locală (Development)**

#### **1. Start PostgreSQL**

**Cu pgAdmin (dacă ai pe Windows):**
- Deschide pgAdmin
- Create server: localhost:5432, user: postgres, password: postgres
- Create database: kubexplain_db
- Run script-ul din `init.sql` în pgAdmin

**Sau cu command line:**
```bash
# Dacă ai PostgreSQL instalat local
createdb -U postgres kubexplain_db

# Rulează init script-ul
psql -U postgres -d kubexplain_db -f init.sql
```

#### **2. Build Backend**

```bash
cd backend
mvn clean install
mvn spring-boot:run
```

Backend va fi disponibil la: http://localhost:8080

#### **3. Start Frontend**

```bash
cd frontend
npm install
npm start
```

Frontend va fi disponibil la: http://localhost:3000

---

## 🧪 Testing Endpoints

### **Register (POST)**
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "john_doe",
    "email": "john@example.com",
    "password": "SecurePass123!"
  }'
```

### **Login (POST)**
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "john@example.com",
    "password": "SecurePass123!"
  }'
```

**Response:**
```json
{
  "token": "token_1_1702168800000",
  "username": "john_doe",
  "email": "john@example.com",
  "userId": 1
}
```

### **Save Chat (POST)**
```bash
curl -X POST http://localhost:8080/api/chat/save \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "userMessage": "What is Kubernetes?",
    "aiResponse": "Kubernetes is an orchestration platform..."
  }'
```

### **Get Chat History (GET)**
```bash
curl http://localhost:8080/api/chat/history/1
```

---

## 📊 Database Schema

### **users table**
```sql
id (PK) | username | email | password (hashed) | created_at
```

### **chats table**
```sql
id (PK) | user_id (FK) | user_message | ai_response | created_at
```

---

## 🔐 Security Notes

- ✅ Passwords sunt hashed cu BCrypt
- ✅ Tokens sunt generați (în producție, se poate implementa JWT proper)
- ✅ CORS este enabled pentru requests de pe frontend
- ✅ Input validation pe backend

---

## 📝 Modificări în Frontend

### **app.js - Auth Functions**
```javascript
// Login handler - trimite credențiale la backend
document.getElementById('login-form').addEventListener('submit', async (e) => { ... })

// Register handler - crea cont nou
document.getElementById('register-form').addEventListener('submit', async (e) => { ... })

// Logout - șterge token și reload
document.getElementById('logout-btn').addEventListener('click', () => { ... })
```

### **index.html - UI Elements**
- Modal Login (id="login-form-container")
- Modal Register (id="register-form-container")
- Butoane Login/Logout (id="auth-btn", id="logout-btn")
- User info display (id="user-info")

### **style.css - Stil Modal**
- `.auth-modal` - Container modal
- `.auth-form` - Form styling
- `.form-group` - Input group styling
- `.auth-message` - Error/Success messages

---

## 📝 Modificări Backend

### **Noi Fișiere:**
- `UserRepository.java` - JPA repository
- `ChatRepository.java` - JPA repository
- `User.java` - Entity
- `Chat.java` - Entity
- `AuthController.java` - Endpoints auth
- `ChatController.java` - Endpoints chat
- `SecurityConfig.java` - Password encryption config

### **Modificări pom.xml:**
- ✅ PostgreSQL driver
- ✅ Spring Security
- ✅ JWT (jjwt) libraries

### **Modificări application.properties:**
- ✅ PostgreSQL connection URL
- ✅ Database username/password
- ✅ Hibernate DDL auto (create-drop/update)

---

## 🐛 Troubleshooting

### **PostgreSQL connection failed**
```
Soluție: Verifica că PostgreSQL rulează pe localhost:5432
         Verifica credentialele în application.properties
         Verifica că baza de date kubexplain_db există
```

### **Login nu funcționează**
```
Soluție: Asigură-te că user e creat în baza de date
         Verifica console logs în browser (DevTools F12)
         Verifica backend logs pentru errors
```

### **CORS errors**
```
Soluție: CORS e enabled pentru "*" în backend
         Verifica că frontend URL e corect în API_URL constant
```

### **Docker not working**
```
Soluție: docker-compose up -d --build
         Verifica docker is running
         docker-compose logs backend  (pentru debug)
```

---

## 🚀 Next Steps

1. **Implement proper JWT tokens** (biblioteci: io.jsonwebtoken)
2. **Add token validation middleware** pe endpoints
3. **Implement AI integration** (OpenAI, Gemini, etc.)
4. **Add email verification** pentru register
5. **Add password reset** functionality
6. **Implement chat persistence** cu full history
7. **Add rate limiting** pentru API
8. **Deploy to production** (AWS, Azure, etc.)

---

## 📞 Support

Dacă ai probleme, verifică:
1. Console browser (F12) pentru frontend errors
2. Backend logs în terminal
3. PostgreSQL connection în pgAdmin
4. Docker status cu `docker ps`

