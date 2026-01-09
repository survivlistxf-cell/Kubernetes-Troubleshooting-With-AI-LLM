# 🏗️ Architecture & Component Diagram

## 📐 System Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                         USER BROWSER                                 │
│                  (http://localhost:3000)                             │
└──────────────────────┬──────────────────────────────────────────────┘
                       │
                       │ HTTP Requests
                       │ (fetch API)
                       ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      FRONTEND LAYER                                  │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │ Node.js + Express Server (Port 3000)                        │   │
│  │ ├─ index.html (View)                                        │   │
│  │ ├─ style.css (Styling)                                      │   │
│  │ └─ app.js (Controllers + Auth Logic)                        │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  Authentication UI:                                                  │
│  ├─ Login Modal                                                     │
│  │  ├─ Email input                                                  │
│  │  ├─ Password input                                               │
│  │  └─ Submit button → /api/auth/login                             │
│  │                                                                   │
│  ├─ Register Modal                                                  │
│  │  ├─ Username input                                               │
│  │  ├─ Email input                                                  │
│  │  ├─ Password input                                               │
│  │  ├─ Confirm password input                                       │
│  │  └─ Submit button → /api/auth/register                          │
│  │                                                                   │
│  └─ Sidebar                                                         │
│     ├─ Login Button (before auth)                                   │
│     ├─ User Info (after auth)                                       │
│     └─ Logout Button (after auth)                                   │
│                                                                      │
│  localStorage Management:                                           │
│  ├─ authToken (from login response)                                 │
│  └─ currentUser (username)                                          │
└──────────────────────┬──────────────────────────────────────────────┘
                       │
                       │ REST API Calls (JSON)
                       │ - POST /api/auth/register
                       │ - POST /api/auth/login
                       │ - POST /api/chat/save
                       │ - GET /api/chat/history/{userId}
                       ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      BACKEND LAYER                                   │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │ Spring Boot Application (Port 8080)                          │   │
│  │                                                              │   │
│  │ ┌──────────────────────────────────────────────────────┐   │   │
│  │ │ AuthController                                       │   │   │
│  │ ├─ POST /api/auth/register                            │   │   │
│  │ │  └─ UserRepository.save() + BCrypt.encode()         │   │   │
│  │ │                                                      │   │   │
│  │ └─ POST /api/auth/login                               │   │   │
│  │    └─ UserRepository.findByEmail() + password match   │   │   │
│  └──────────────────────────────────────────────────────┘   │   │
│  │                                                              │   │
│  │ ┌──────────────────────────────────────────────────────┐   │   │
│  │ │ ChatController                                       │   │   │
│  │ ├─ POST /api/chat/save                                │   │   │
│  │ │  └─ ChatRepository.save()                           │   │   │
│  │ │                                                      │   │   │
│  │ └─ GET /api/chat/history/{userId}                     │   │   │
│  │    └─ ChatRepository.findByUserOrderByCreatedAtDesc() │   │   │
│  └──────────────────────────────────────────────────────┘   │   │
│  │                                                              │   │
│  │ Security Configuration:                                     │   │
│  │ └─ BCryptPasswordEncoder (strength: 10)                    │   │
│  │                                                              │   │
│  │ Exception Handling:                                         │   │
│  │ ├─ Invalid email/password                                  │   │
│  │ ├─ Duplicate email/username                                │   │
│  │ ├─ Missing required fields                                 │   │
│  │ └─ User not found                                          │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                      │
│  Dependency Injection:                                              │
│  ├─ UserRepository                                                  │
│  ├─ ChatRepository                                                  │
│  ├─ PasswordEncoder                                                 │
│  └─ EntityManager                                                   │
└──────────────────────┬──────────────────────────────────────────────┘
                       │
                       │ JDBC/SQL Queries
                       │ (via Hibernate ORM)
                       ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    DATABASE LAYER                                    │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │ PostgreSQL 16 (Alpine)                                       │   │
│  │ Database: kubexplain_db                                      │   │
│  │                                                              │   │
│  │ ┌───────────────────────────────┐ ┌──────────────────────┐ │   │
│  │ │ USERS TABLE                   │ │ CHATS TABLE          │ │   │
│  │ ├───────────────────────────────┤ ├──────────────────────┤ │   │
│  │ │ id (PK)                       │ │ id (PK)              │ │   │
│  │ │ username (UNIQUE, NOT NULL)   │ │ user_id (FK) ───────┼─┼───→ users.id
│  │ │ email (UNIQUE, NOT NULL)      │ │ user_message (TEXT)  │ │   │
│  │ │ password (NOT NULL) [hashed]  │ │ ai_response (TEXT)   │ │   │
│  │ │ created_at (TIMESTAMP)        │ │ created_at           │ │   │
│  │ └───────────────────────────────┘ └──────────────────────┘ │   │
│  │                                                              │   │
│  │ Indexes (Performance):                                       │   │
│  │ ├─ users.id (PK)                                            │   │
│  │ ├─ users.email (UNIQUE)                                     │   │
│  │ ├─ users.username (UNIQUE)                                  │   │
│  │ ├─ chats.id (PK)                                            │   │
│  │ ├─ chats.user_id (FK) → Fast user lookups                  │   │
│  │ └─ chats.created_at → Fast date sorting                    │   │
│  │                                                              │   │
│  │ Relationships:                                              │   │
│  │ └─ 1 User ───→ Many Chats (1:N)                            │   │
│  │    └─ ON DELETE CASCADE (clean up orphaned records)         │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 🔀 Data Flow Diagrams

### **Registration Flow**

```
1. User Input
   ┌─────────────────────┐
   │ Username            │
   │ Email               │
   │ Password            │
   │ Confirm Password    │
   └──────────┬──────────┘
              │
              ▼
2. Frontend Validation
   ├─ Password match?
   ├─ Email format?
   └─ All fields filled?
              │
              ▼
3. POST /api/auth/register
   ┌──────────────────────┐
   │ {username, email,    │
   │  password}           │
   └──────────┬───────────┘
              │
              ▼
4. Backend Processing
   ├─ Check if email exists → UserRepository.findByEmail()
   ├─ Check if username exists → UserRepository.findByUsername()
   ├─ If not: Hash password → BCryptPasswordEncoder.encode()
   └─ Save user → UserRepository.save()
              │
              ▼
5. Database Insert
   INSERT INTO users (username, email, password, created_at)
   VALUES (?, ?, ?, NOW())
              │
              ▼
6. Response
   ┌──────────────────────┐
   │ Success Message      │
   └──────────┬───────────┘
              │
              ▼
7. Frontend Action
   └─ Switch to Login form
```

### **Login Flow**

```
1. User Input
   ┌─────────────────────┐
   │ Email               │
   │ Password            │
   └──────────┬──────────┘
              │
              ▼
2. POST /api/auth/login
   ┌──────────────────────┐
   │ {email, password}    │
   └──────────┬───────────┘
              │
              ▼
3. Backend Processing
   ├─ Query database → UserRepository.findByEmail(email)
   │  └─ SELECT * FROM users WHERE email = ?
   │
   ├─ Compare passwords
   │  └─ BCryptPasswordEncoder.matches(inputPassword, storedPassword)
   │
   └─ If match: Generate token & return user data
              │
              ▼
4. Response
   ┌──────────────────────┐
   │ {token,              │
   │  username,           │
   │  userId,             │
   │  email}              │
   └──────────┬───────────┘
              │
              ▼
5. Frontend Storage
   ├─ localStorage.setItem('authToken', token)
   └─ localStorage.setItem('currentUser', username)
              │
              ▼
6. UI Update
   ├─ Hide Login button
   ├─ Show username in sidebar
   ├─ Show Logout button
   └─ Close modal
```

### **Chat Save Flow**

```
1. User sends message
   ┌──────────────────────┐
   │ User: "What is K8s?" │
   └──────────┬───────────┘
              │
              ▼
2. Backend processes
   └─ generateResponse(message) → AI response (or stub)
              │
              ▼
3. POST /api/chat/save
   ┌──────────────────────────────┐
   │ {userId, userMessage,        │
   │  aiResponse}                 │
   └──────────┬────────────────────┘
              │
              ▼
4. Backend Processing
   ├─ Find user → UserRepository.findById(userId)
   ├─ Create Chat object
   └─ Save to database → ChatRepository.save(chat)
              │
              ▼
5. Database Insert
   INSERT INTO chats (user_id, user_message, ai_response, created_at)
   VALUES (?, ?, ?, NOW())
              │
              ▼
6. Response
   ┌──────────────────────┐
   │ {message, chatId}    │
   └──────────┬───────────┘
              │
              ▼
7. Frontend Update
   ├─ Display message in chat
   ├─ Display AI response
   └─ Show timestamp
```

### **Chat History Retrieval**

```
1. User clicks "Chat History"
   ┌──────────────────┐
   │ userId from      │
   │ localStorage     │
   └──────────┬───────┘
              │
              ▼
2. GET /api/chat/history/{userId}
              │
              ▼
3. Backend Processing
   ├─ Find user → UserRepository.findById(userId)
   ├─ Query chats → ChatRepository.findByUserOrderByCreatedAtDesc()
   │  └─ SELECT * FROM chats WHERE user_id = ? ORDER BY created_at DESC
   │
   └─ Return results
              │
              ▼
4. Response
   ┌──────────────────────────────┐
   │ {chats: [                    │
   │   {id, userMessage,          │
   │    aiResponse, createdAt},   │
   │   {...}                      │
   │ ], count: N}                 │
   └──────────┬────────────────────┘
              │
              ▼
5. Frontend Render
   └─ Display all chats in history panel
      with timestamps
```

---

## 🔐 Security Architecture

```
┌─────────────────────────────────────────────────────────┐
│                  SECURITY LAYERS                         │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  1. INPUT VALIDATION (Frontend)                          │
│  ├─ Email format check                                  │
│  ├─ Password strength (optional)                        │
│  ├─ Required fields validation                          │
│  └─ Show user-friendly error messages                   │
│                                                          │
│  2. NETWORK SECURITY (HTTPS in Production)              │
│  ├─ Secure transmission                                 │
│  └─ SSL/TLS encryption                                  │
│                                                          │
│  3. BACKEND VALIDATION                                   │
│  ├─ Duplicate email/username check                      │
│  ├─ Required fields validation                          │
│  ├─ Email uniqueness constraint (DB)                    │
│  └─ Username uniqueness constraint (DB)                 │
│                                                          │
│  4. PASSWORD SECURITY                                    │
│  ├─ BCrypt hashing (10 rounds)                          │
│  │  ├─ Never stored as plain text                       │
│  │  ├─ Safe comparison: encoder.matches()               │
│  │  └─ Even DBA can't see original passwords            │
│  │                                                       │
│  └─ Example hashed password:                            │
│     $2a$10$slYQmyNdGzin7olVN3p5/.O9wO2kxaq7VVrXvnHYNrK...
│                                                          │
│  5. DATABASE CONSTRAINTS                                │
│  ├─ Unique constraints (email, username)                │
│  ├─ Foreign key constraints (user_id → users.id)        │
│  ├─ NOT NULL constraints                                │
│  ├─ Referential integrity                               │
│  └─ CASCADE delete (clean orphaned records)             │
│                                                          │
│  6. SESSION MANAGEMENT (Future: JWT)                    │
│  ├─ Token generation on login                           │
│  ├─ Token validation on protected routes                │
│  ├─ Token expiration (time-based)                       │
│  └─ Token refresh mechanism                             │
│                                                          │
│  7. CORS CONFIGURATION                                  │
│  ├─ Allowed origins (currently *)                       │
│  ├─ Allowed methods (GET, POST, PUT, DELETE)            │
│  ├─ Allowed headers (all)                               │
│  └─ Credentials handling                                │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

---

## 📊 Database Relationship Diagram

```
                         ┌─────────────────┐
                         │  USERS TABLE    │
                         ├─────────────────┤
                         │ id (PK)         │◄─────────┐
                         │ username        │          │
                         │ email           │          │
                         │ password        │          │
                         │ created_at      │          │
                         └─────────────────┘          │
                                                      │
                                            1 ─── ∞   │
                                                      │
                         ┌─────────────────────────────┤
                         │                             │
                    ┌────┴────┐                   ┌────┴────┐
                    │ CHATS   │                   │ FOREIGN │
                    │ TABLE   │                   │ KEY     │
                    ├─────────┤                   └─────────┘
                    │ id (PK) │
                    │user_id  │ ───┐  References  ┌──┐
                    │(FK)     │    │  users.id    │ id
                    │user_msg │    └──────────────┤
                    │ai_resp  │                   └──┘
                    │created  │
                    └─────────┘


CASCADE DELETE:
When a user is deleted:
   Users(id=1) ── DELETE──→ Chats(user_id=1) are also deleted
                           (automatic cleanup)
```

---

## 🔌 Component Interactions

```
                    ┌─────────────────┐
                    │   WEB BROWSER   │
                    │                 │
                    │  - HTML DOM     │
                    │  - CSS Styles   │
                    │  - JavaScript   │
                    │  - localStorage │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │   FETCH API     │
                    │                 │
                    │  - HTTP POST    │
                    │  - HTTP GET     │
                    │  - JSON body    │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │  BACKEND (REST) │
                    │                 │
                    │  - Controllers  │
                    │  - Validation   │
                    │  - Encryption   │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │   JPA/ORM       │
                    │                 │
                    │  - Entities     │
                    │  - Repositories │
                    │  - Mapping      │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │    DATABASE     │
                    │                 │
                    │  - Tables       │
                    │  - Indexes      │
                    │  - Constraints  │
                    └─────────────────┘
```

---

## 🚀 Deployment Architecture

```
┌─────────────────────────────────────────────────────────┐
│          Docker Compose (Local/Staging)                 │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  ┌───────────────────┐  ┌──────────────┐               │
│  │  PostgreSQL       │  │  Frontend    │               │
│  │  Container        │  │  Container   │               │
│  │                   │  │              │               │
│  │ ├─ Port 5432      │  │ ├─ Port 3000 │               │
│  │ ├─ Volume:        │  │ ├─ Node.js   │               │
│  │ │  postgres_data  │  │ │ Express    │               │
│  │ └─ Health check   │  │ └─ npm start │               │
│  │                   │  │              │               │
│  └───────────────────┘  └──────────────┘               │
│           ▲                     ▲                       │
│           │                     │                       │
│        Depends on          Depends on                   │
│           │                     │                       │
│  ┌────────┴────────────────────┴─────────────┐         │
│  │       Backend Container                    │         │
│  │                                            │         │
│  │  ├─ Spring Boot Application                │         │
│  │  ├─ Port 8080                             │         │
│  │  ├─ JPA/Hibernate ORM                     │         │
│  │  ├─ Controllers & Services                │         │
│  │  ├─ Environment variables:                │         │
│  │  │  ├─ SPRING_DATASOURCE_URL=             │         │
│  │  │  │  jdbc:postgresql://postgres:5432... │         │
│  │  │  ├─ SPRING_DATASOURCE_USERNAME         │         │
│  │  │  └─ SPRING_DATASOURCE_PASSWORD         │         │
│  │  └─ Health check endpoint: /api/hello     │         │
│  └────────────────────────────────────────────┘        │
│                                                          │
│  Network: proiect-network (bridge)                      │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

---

## 🎯 Key Design Decisions

| Component | Technology | Reason |
|-----------|-----------|--------|
| Database | PostgreSQL | ACID compliance, reliability, scalability |
| ORM | Spring Data JPA | Reduces boilerplate, type-safe, standard |
| Password | BCrypt | Industry standard, salted hashing, slow by design |
| Frontend | Vanilla JS | No build step, simple, direct DOM manipulation |
| Backend | Spring Boot | Java ecosystem, production-ready, well-documented |
| Deployment | Docker | Consistency, isolation, easy scaling |
| Container | Alpine Linux | Lightweight, minimal attack surface |

---

**Architecture created: December 10, 2024** 📐
**Status: Production-ready** ✅
