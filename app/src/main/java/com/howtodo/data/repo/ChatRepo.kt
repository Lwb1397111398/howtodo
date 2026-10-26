package com.howtodo.data.repo

import com.howtodo.data.db.AppDatabase
import com.howtodo.data.db.ConversationEntity
import com.howtodo.data.db.MessageEntity
import java.util.UUID

class ChatRepo(private val db: AppDatabase) {

    private val convDao get() = db.conversationDao()
    private val msgDao get() = db.messageDao()

    fun observeConversations(projectId: String?) = convDao.observeByProject(projectId)

    fun observeAllConversations() = convDao.observeAll()

    fun observeMessages(conversationId: String) = msgDao.observeByConversation(conversationId)

    suspend fun conversation(id: String) = convDao.byId(id)

    /** 该范围（某工作或全局）最近的一段会话；没有返回 null */
    suspend fun latestConversation(projectId: String?) = convDao.latest(projectId)

    suspend fun createConversation(projectId: String?, title: String): ConversationEntity {
        val now = System.currentTimeMillis()
        val c = ConversationEntity(
            id = UUID.randomUUID().toString().replace("-", ""),
            projectId = projectId,
            title = title.take(40).ifBlank { "新对话" },
            createdAt = now,
            updatedAt = now
        )
        convDao.insert(c)
        return c
    }

    suspend fun rename(id: String, title: String) = convDao.rename(id, title)

    suspend fun deleteConversation(id: String) {
        msgDao.deleteByConversation(id)
        convDao.delete(id)
    }

    suspend fun append(
        conversationId: String,
        role: String,
        content: String,
        citations: String? = null,
        model: String? = null,
        promptTokens: Int = 0,
        completionTokens: Int = 0,
        status: String = "OK",
        error: String? = null
    ): MessageEntity {
        val now = System.currentTimeMillis()
        val m = MessageEntity(
            id = UUID.randomUUID().toString().replace("-", ""),
            conversationId = conversationId,
            role = role,
            content = content,
            citations = citations,
            model = model,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            status = status,
            error = error,
            createdAt = now
        )
        msgDao.insert(m)
        convDao.touch(conversationId, now)
        return m
    }

    suspend fun recentMessages(conversationId: String, limit: Int = 8) =
        msgDao.recent(conversationId, limit).reversed()
}
