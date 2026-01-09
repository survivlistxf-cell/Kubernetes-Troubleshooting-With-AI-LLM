# 🧪 Test Examples - PostgreSQL & Auth

## API Testing Examples

### **1. Register Nou User**

#### cURL
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "john_doe",
    "email": "john@example.com",
    "password": "SecurePass123!"
  }'
```

#### Response
```json
{
  "message": "User registered successfully"
}
```

---

### **2. Login User**

#### cURL
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "john@example.com",
    "password": "SecurePass123!"
  }'
```

#### Response
```json
{
  "token": "token_1_1702168800000",
  "username": "john_doe",
  "email": "john@example.com",
  "userId": 1
}
```

---

### **3. Save Chat Message**

#### cURL
```bash
curl -X POST http://localhost:8080/api/chat/save \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "userMessage": "What is Kubernetes?",
    "aiResponse": "Kubernetes is an open-source container orchestration platform that automates many of the manual processes involved in deploying, managing, and scaling containerized applications."
  }'
```

#### Response
```json
{
  "message": "Chat saved successfully",
  "chatId": 1
}
```

---

### **4. Get Chat History**

#### cURL
```bash
curl http://localhost:8080/api/chat/history/1
```

#### Response
```json
{
  "chats": [
    {
      "id": 1,
      "user": {
        "id": 1,
        "username": "john_doe",
        "email": "john@example.com"
      },
      "userMessage": "What is Kubernetes?",
      "aiResponse": "Kubernetes is an open-source container orchestration platform...",
      "createdAt": "2024-12-10T10:30:45.123456"
    }
  ],
  "count": 1
}
```

---

## 🧪 Test in Postman

### **Step 1: Import cURL requests**
1. Open Postman
2. New → HTTP
3. Paste cURL din exemplele de mai sus
4. Click "Send"

### **Step 2: Store Token for Later Requests**
1. Login successful → copy token
2. Use for protected endpoints (în viitor)

---

## 🌐 Test in Browser (Frontend)

### **Test Register:**
1. Open http://localhost:3000
2. Click "👤 Login" button
3. Click "Register here" link
4. Fill form:
   - Username: `testuser`
   - Email: `test@email.com`
   - Password: `Test123!`
   - Confirm: `Test123!`
5. Click "Register"
6. See success message ✅

### **Test Login:**
1. Click "Login here" link
2. Fill form:
   - Email: `test@email.com`
   - Password: `Test123!`
3. Click "Login"
4. See username in sidebar ✅
5. See "Logout" button ✅

### **Test Logout:**
1. Click "Logout" button
2. Page reloads
3. See "Login" button again ✅

---

## 💾 Database Verification

### **Verify Users Table**
```sql
-- În pgAdmin Query Tool sau psql
SELECT * FROM users;

-- Should show:
-- id | username  | email           | password (hashed) | created_at
-- 1  | testuser  | test@email.com  | $2a$10$... (hashed)
```

### **Verify Chats Table**
```sql
SELECT * FROM chats;

-- Should show:
-- id | user_id | user_message | ai_response | created_at
-- 1  | 1       | What is...   | Kubernetes... | 2024-12-10...
```

---

## ❌ Error Testing

### **Test Invalid Email**
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "invalid@example.com",
    "password": "SomePass"
  }'
```

**Expected:** 
```json
{
  "message": "Invalid email or password"
}
```

### **Test Duplicate Email**
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "another_user",
    "email": "test@email.com",
    "password": "Pass123!"
  }'
```

**Expected:**
```json
{
  "message": "Email already registered"
}
```

### **Test Missing Fields**
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "onlyusername"
  }'
```

**Expected:**
```json
{
  "message": "Missing required fields"
}
```

---

## 📊 Performance Testing

### **Load Test - Multiple Users**
```bash
#!/bin/bash
for i in {1..10}; do
  curl -X POST http://localhost:8080/api/auth/register \
    -H "Content-Type: application/json" \
    -d '{
      "username": "user'$i'",
      "email": "user'$i'@example.com",
      "password": "Pass123!'$i'"
    }'
  echo "User $i registered"
done
```

### **Check Database Size**
```sql
SELECT 
  schemaname,
  tablename,
  pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS size
FROM pg_tables
WHERE schemaname = 'public'
ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;
```

---

## 🔍 Debug Queries

### **Find User by Email**
```sql
SELECT * FROM users WHERE email = 'test@email.com';
```

### **Count User Registrations**
```sql
SELECT COUNT(*) FROM users;
```

### **Get All Chats for User**
```sql
SELECT * FROM chats 
WHERE user_id = 1 
ORDER BY created_at DESC;
```

### **Get Chat Statistics**
```sql
SELECT 
  u.username,
  COUNT(c.id) as chat_count,
  MAX(c.created_at) as last_chat
FROM users u
LEFT JOIN chats c ON u.id = c.user_id
GROUP BY u.id, u.username;
```

---

## ✅ Complete Test Checklist

- [ ] Register works from UI
- [ ] Login works from UI
- [ ] Username displays in sidebar
- [ ] Logout works
- [ ] Users table populated in DB
- [ ] Password is hashed (not plain text)
- [ ] Register fails with duplicate email
- [ ] Login fails with wrong password
- [ ] Can save chat messages
- [ ] Can retrieve chat history
- [ ] Chats linked to correct user
- [ ] Timestamps are correct
- [ ] No SQL errors in backend logs

---

## 🚀 Ready to Test!

Acum poti:
1. ✅ Crea conturi de utilizator
2. ✅ Login/Logout
3. ✅ Salva conversații
4. ✅ Vedea history-ul chat-urilor

**Next:** Integrare AI API! 🤖
