package com.example.data.repository

import com.example.data.local.AssistantDao
import com.example.data.local.ChatMessageEntity
import com.example.data.local.MemoryEntity
import com.example.data.local.TaskEntity
import kotlinx.coroutines.flow.Flow

class AssistantRepository(private val dao: AssistantDao) {

    val allMessages: Flow<List<ChatMessageEntity>> = dao.getAllMessages()
    val allMemories: Flow<List<MemoryEntity>> = dao.getAllMemories()
    val allTasks: Flow<List<TaskEntity>> = dao.getAllTasks()

    suspend fun insertMessage(message: ChatMessageEntity): Long = dao.insertMessage(message)

    suspend fun clearAllMessages() = dao.clearAllMessages()

    suspend fun deleteSeededWelcomeMessages() = dao.deleteSeededWelcomeMessages()

    suspend fun insertMemory(memory: MemoryEntity): Long = dao.insertMemory(memory)

    suspend fun deleteMemoryById(id: Long) = dao.deleteMemoryById(id)

    suspend fun clearAllMemories() = dao.clearAllMemories()

    suspend fun insertTask(task: TaskEntity): Long = dao.insertTask(task)

    suspend fun toggleTaskCompleted(task: TaskEntity) {
        dao.updateTask(task.copy(isCompleted = !task.isCompleted))
    }

    suspend fun deleteTaskById(id: Long) = dao.deleteTaskById(id)
}
