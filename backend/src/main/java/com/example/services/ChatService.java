package com.example.services;

import com.example.entities.Chat;
import com.example.entities.Conversation;
import com.example.entities.User;
import com.example.repositories.ChatAttachmentRepository;
import com.example.repositories.ChatRepository;
import com.example.repositories.ConversationContextRepository;
import com.example.repositories.ConversationRepository;
import com.example.repositories.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class ChatService {

    private final ChatRepository chatRepository;
    private final UserRepository userRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationContextRepository conversationContextRepository;
    private final ChatAttachmentRepository chatAttachmentRepository;
    private final AttachmentService attachmentService;
    private final AiForwardingService aiForwardingService;

    public ChatService(
            ChatRepository chatRepository,
            UserRepository userRepository,
            ConversationRepository conversationRepository,
            ConversationContextRepository conversationContextRepository,
            ChatAttachmentRepository chatAttachmentRepository,
            AttachmentService attachmentService,
            AiForwardingService aiForwardingService) {
        this.chatRepository = chatRepository;
        this.userRepository = userRepository;
        this.conversationRepository = conversationRepository;
        this.conversationContextRepository = conversationContextRepository;
        this.chatAttachmentRepository = chatAttachmentRepository;
        this.attachmentService = attachmentService;
        this.aiForwardingService = aiForwardingService;
    }

    public Map<String, Object> persistChat(
            String userIdValue, String conversationId,
            String userMessage, String aiResponse, Object attachmentsObj) {
        if (userIdValue == null)
            return null;

        try {
            Long userId = Long.parseLong(userIdValue); // converteste userIdValue la Long, cum cere baza de date
            final String conv = (conversationId == null || conversationId.isBlank())
                    ? UUID.randomUUID().toString()
                    : conversationId; // creaza un id unic pentru conversatie pentru conversatii noi
                                      // daca conversatie exista, foloseste id-ul existent

            // foloseste AtomicReference pentru a putea seta valoarea ca fiind finala in
            // lambda
            // ca sa stim ca nu se va modifica valoarea in timpul executarii functiei lambda
            var chatIdRef = new java.util.concurrent.atomic.AtomicReference<Long>(null);
            var attMetaRef = new java.util.concurrent.atomic.AtomicReference<List<Map<String, Object>>>(List.of());
            // folosim var pentru a fi mai usor de citit,
            // java se uita in dreapta egalului pentru tipul variabilei

            // jpa cauta in baza de date user-ul cu id-ul specificat
            // si il mapeaza pe variabila user din functia lambda
            // public interface UserRepository extends JpaRepository<User, Long>
            userRepository.findById(userId).ifPresent(user -> {
                ensureConversation(user, conv, userMessage);
                Chat chat = new Chat();
                chat.setUser(user);
                chat.setConversationId(conv);
                chat.setUserMessage(userMessage);
                chat.setAiResponse(aiResponse);

                // salveaza mesaj user + ai response (chat) in db si primeste si cheie primara
                Chat saved = chatRepository.save(chat);
                // Ia id-ul generat automat de db si il salveaza in chatIdRef (pentru a fi
                // returnat frontend-ului)
                // Trebuie sa folosim chatIdRef in functie lambda
                // pentru a putea scoate valoarea din functie
                chatIdRef.set(saved.getId());

                // salvam metadatele si continutul atasamentelor in database cu functia
                // saveAttachmentsFromRequest
                var savedAttachments = attachmentService.saveAttachmentsFromRequest(conv, user, saved, attachmentsObj);
                List<Map<String, Object>> metas = new ArrayList<>();
                for (var a : savedAttachments) {
                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("id", a.getId());
                    meta.put("name", Objects.toString(a.getFileName(), ""));
                    meta.put("type", Objects.toString(a.getMimeType(), "application/octet-stream"));
                    meta.put("size", a.getSizeBytes() != null ? a.getSizeBytes() : 0L);
                    metas.add(meta);
                }
                attMetaRef.set(metas);
            });

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("conversationId", conv);
            result.put("chatId", chatIdRef.get()); // aici avem nevoie de valoarea din functia lambda
            result.put("attachments", attMetaRef.get()); // si aici
            return result;
        } catch (NumberFormatException e) {
            System.err.println("Invalid userId for chat persistence: " + userIdValue);
            return null;
        }
    }

    public Chat saveSimpleChat(User user, String userMessage, String aiResponse) {
        Chat chat = new Chat();
        chat.setUser(user);
        chat.setUserMessage(userMessage);
        chat.setAiResponse(aiResponse);
        return chatRepository.save(chat);
    }

    public void updateFeedback(String conversationId, Integer score) {
        chatRepository.findFirstByConversationIdOrderByCreatedAtDesc(conversationId).ifPresent(chat -> {
            chat.setFeedback(score);
            chatRepository.save(chat);
        });
    }

    // ── Conversation management ──

    public void ensureConversation(User user, String conversationId, String userMessage) {
        try {
            if (conversationId == null || conversationId.isBlank())
                return;

            Conversation c = conversationRepository.findById(conversationId).orElse(null);
            if (c == null) {
                c = new Conversation();
                c.setConversationId(conversationId);
                c.setUser(user);
                c.setTitle(deriveTitle(userMessage));
                c.setTitleCustom(false);
            }

            // derivam titlul conversatiei din mesaj pentru mai mult sens
            if (!Boolean.TRUE.equals(c.getTitleCustom())) {
                if (c.getTitle() == null || c.getTitle().isBlank() || "Conversation".equals(c.getTitle())) {
                    c.setTitle(deriveTitle(userMessage));
                }
            }
            c.setUpdatedAt(LocalDateTime.now());
            // daca este conversatie noua, o salvam in baza de date
            // daca deja exista, JPA doar ii da un update la timestamp
            conversationRepository.save(c);
        } catch (Exception ignored) {
        }
    }

    @Transactional
    public boolean deleteConversation(String conversationId) {
        conversationContextRepository.deleteByConversationId(conversationId);
        chatAttachmentRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)
                .forEach(a -> chatAttachmentRepository.deleteById(a.getId()));
        chatRepository.deleteByConversationId(conversationId);

        try {
            conversationContextRepository.flush();
            chatRepository.flush();
        } catch (Exception ignored) {
        }

        if (conversationRepository.existsById(conversationId)) {
            conversationRepository.deleteById(conversationId);
        }
        return true;
    }

    public boolean conversationExists(String conversationId) {
        return conversationRepository.existsById(conversationId)
                || chatRepository.existsByConversationId(conversationId);
    }

    // ── Title management ──

    public void autoGenerateTitleIfNeeded(String conversationId, String userId, String requestId) {
        if (conversationId == null || conversationId.isBlank())
            return;
        try {
            Conversation c = conversationRepository.findById(conversationId).orElse(null);
            if (c == null || Boolean.TRUE.equals(c.getTitleCustom()))
                return;

            List<Chat> chats = chatRepository.findByConversationIdOrderByCreatedAtDesc(conversationId);
            if (chats != null && chats.size() == 1) {
                regenerateTitle(conversationId, userId, requestId, false);
            }
        } catch (Exception ignored) {
        }
    }

    public String regenerateTitle(String conversationId, String userId, String requestId, boolean force) {
        try {
            if (conversationId == null || conversationId.isBlank())
                return null;

            Conversation conv = conversationRepository.findById(conversationId).orElse(null);
            if (conv == null)
                return null;
            if (!force && Boolean.TRUE.equals(conv.getTitleCustom()))
                return conv.getTitle();

            List<Chat> chatsDesc = chatRepository.findByConversationIdOrderByCreatedAtDesc(conversationId);
            StringBuilder sb = new StringBuilder();
            int used = 0;
            for (Chat c : chatsDesc) {
                if (used >= 12)
                    break;
                if (c.getUserMessage() != null && !c.getUserMessage().isBlank())
                    sb.append("User: ").append(c.getUserMessage().replaceAll("\\r?\\n", " ")).append("\n");
                if (c.getAiResponse() != null && !c.getAiResponse().isBlank())
                    sb.append("Assistant: ").append(c.getAiResponse().replaceAll("\\r?\\n", " ")).append("\n");
                used++;
            }

            String prompt = "Generate a short title (max 8 words) that summarizes this conversation. " +
                    "Return ONLY the title, no quotes, no punctuation at the end.\n\n" + sb.toString().trim();

            AiForwardingService.ForwardResult forwardResult = aiForwardingService.forward(userId, conversationId, prompt, null, requestId);
            String aiTitle = forwardResult != null ? forwardResult.text() : null;
            if (aiForwardingService.isAiHttpError(aiTitle))
                aiTitle = null;

            String finalTitle;
            if (aiTitle == null || aiTitle.isBlank()) {
                Collections.reverse(chatsDesc);
                String firstUser = chatsDesc.stream()
                        .map(Chat::getUserMessage)
                        .filter(m -> m != null && !m.isBlank())
                        .findFirst().orElse(null);
                finalTitle = deriveTitle(firstUser);
            } else {
                finalTitle = aiTitle.replaceAll("\\r?\\n", " ").trim();
                if (finalTitle.length() > 255)
                    finalTitle = finalTitle.substring(0, 255);
            }

            conv.setTitle(finalTitle);
            conv.setTitleCustom(false);
            conv.setUpdatedAt(LocalDateTime.now());
            conversationRepository.save(conv);
            return finalTitle;
        } catch (Exception e) {
            return null;
        }
    }

    public void updateTitle(Conversation conv, String title) {
        conv.setTitle(title.trim());
        conv.setTitleCustom(true);
        conv.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conv);
    }

    // ── Query helpers ──

    public Optional<User> findUser(Long userId) {
        return Optional.ofNullable(userId).flatMap(userRepository::findById);
    }

    public Optional<Conversation> findConversation(String conversationId) {
        return conversationRepository.findById(conversationId);
    }

    public List<Chat> getChatsByConversationAsc(String conversationId) {
        List<Chat> chats = chatRepository.findByConversationIdOrderByCreatedAtDesc(conversationId);
        Collections.reverse(chats);
        return chats;
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<Map<String, Object>> getChatDtosWithAttachments(String conversationId) {
        List<Chat> chats = getChatsByConversationAsc(conversationId);
        List<Map<String, Object>> dtos = new ArrayList<>();
        for (Chat c : chats) {
            List<Map<String, Object>> atts = new ArrayList<>();
            if (c.getId() != null) {
                for (var a : chatAttachmentRepository.findByChatIdOrderByIdAsc(c.getId())) {
                    Map<String, Object> aMap = new LinkedHashMap<>();
                    aMap.put("id", a.getId());
                    aMap.put("name", Objects.toString(a.getFileName(), ""));
                    aMap.put("type", Objects.toString(a.getMimeType(), "application/octet-stream"));
                    aMap.put("size", a.getSizeBytes() != null ? a.getSizeBytes() : 0L);
                    atts.add(aMap);
                }
            }
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", c.getId());
            dto.put("conversationId", Objects.toString(c.getConversationId(), ""));
            dto.put("userMessage", Objects.toString(c.getUserMessage(), ""));
            dto.put("aiResponse", Objects.toString(c.getAiResponse(), ""));
            dto.put("createdAt", c.getCreatedAt());
            dto.put("feedback", c.getFeedback() != null ? c.getFeedback() : 0);
            dto.put("attachments", atts);
            dtos.add(dto);
        }
        return dtos;
    }

    /**
     * Build a list of conversation summaries for a user.
     */
    public List<Map<String, Object>> listConversations(User user) {
        List<Conversation> convRows = conversationRepository.findByUserOrderByUpdatedAtDesc(user);
        List<Map<String, Object>> result = new ArrayList<>();

        for (Conversation c : convRows) {
            if (c == null || c.getConversationId() == null || c.getConversationId().isBlank()) {
                continue;
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("conversationId", c.getConversationId());
            m.put("title", c.getTitle());
            m.put("createdAt", c.getCreatedAt());
            m.put("updatedAt", c.getUpdatedAt());
            result.add(m);
        }

        return result;
    }

    public List<Chat> getChatHistory(User user) {
        return chatRepository.findByUserOrderByCreatedAtDesc(user);
    }

    // ── Fallback response ──

    public String generateFallbackResponse(String message) {
        String lower = message.toLowerCase();
        if (lower.contains("kubernetes") || lower.contains("k8s"))
            return "Kubernetes is an open-source container orchestration platform that automates deploying, managing, and scaling containerized applications.";
        if (lower.contains("docker"))
            return "Docker is a containerization platform that packages your application and all its dependencies into a container.";
        if (lower.contains("hello") || lower.contains("hi"))
            return "Hello! I'm Kubexplain, your AI assistant for Kubernetes and cloud infrastructure questions.";
        if (lower.contains("help"))
            return "I can help with Kubernetes, Docker, container orchestration, cloud infrastructure, and DevOps.";
        return "That's an interesting question! Please try asking about Kubernetes, Docker, or cloud infrastructure.";
    }

    // ── Title derivation (static utility) ──

    public static String deriveTitle(String userMessage) {
        if (userMessage == null)
            return "Conversatie";

        String oneLine = userMessage.replaceAll("\r?\n", " ").replaceAll("\\s+", " ").trim();
        if (oneLine.isBlank())
            return "Conversatie";

        oneLine = oneLine.replaceAll("^(?i)(salut|buna|bună|hey|hello|hi)[,!\\s]+", "").trim();

        if (oneLine.length() > 220 || oneLine.contains("Exception") || oneLine.contains("Traceback")
                || oneLine.contains("kubectl")) {
            return "Diagnosticare / Troubleshooting";
        }

        String cut = oneLine;
        int dot = indexOfFirst(cut, ".", "?", "!", ";");
        if (dot > 20)
            cut = cut.substring(0, dot).trim();

        String[] words = cut.split("\\s+");
        int keep = Math.min(words.length, 8);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keep; i++) {
            if (i > 0)
                sb.append(' ');
            sb.append(words[i]);
        }
        String title = sb.toString().trim();
        if (title.length() > 60)
            title = title.substring(0, 57) + "...";
        if (title.isBlank())
            return "Conversatie";
        return title.substring(0, 1).toUpperCase() + title.substring(1);
    }

    private static int indexOfFirst(String s, String... needles) {
        if (s == null)
            return -1;
        int best = -1;
        for (String n : needles) {
            int idx = s.indexOf(n);
            if (idx >= 0 && (best < 0 || idx < best))
                best = idx;
        }
        return best;
    }
}
