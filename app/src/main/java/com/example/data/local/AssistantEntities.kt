package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val role: String, // "USER" or "ASSISTANT"
    val content: String,
    val emotionTag: String = "NEUTRAL",
    val gestureTag: String = "NONE",
    val actionSummary: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "assistant_memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val fact: String,
    val category: String = "PERSONAL", // PERSONAL, PREFERENCE, WORK, ROUTINE
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "assistant_tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "description")
    val description: String = "",

    @ColumnInfo(name = "due_date")
    val dueDate: Long? = null,

    @ColumnInfo(name = "is_completed")
    val isCompleted: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
