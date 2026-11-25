package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.model.CallDirection
import com.example.data.model.TransportMode
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "call_logs")
data class CallLogEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val peerId: String,
  val peerName: String,
  val peerAddress: String,
  val transportMode: String,
  val direction: String,
  val durationSeconds: Long,
  val timestampMs: Long = System.currentTimeMillis(),
) {
  val transportEnum: TransportMode
    get() =
      if (transportMode == TransportMode.BLUETOOTH.name) TransportMode.BLUETOOTH
      else TransportMode.HOTSPOT_WIFI

  val directionEnum: CallDirection
    get() =
      runCatching { CallDirection.valueOf(direction) }.getOrDefault(CallDirection.OUTGOING)
}

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val peerId: String,
  val peerName: String,
  val peerAddress: String,
  val transportMode: String,
  val text: String,
  val isFromMe: Boolean,
  val delivered: Boolean = false,
  val timestampMs: Long = System.currentTimeMillis(),
) {
  val transportEnum: TransportMode
    get() =
      if (transportMode == TransportMode.BLUETOOTH.name) TransportMode.BLUETOOTH
      else TransportMode.HOTSPOT_WIFI
}

@Dao
interface CallLogDao {
  @Query("SELECT * FROM call_logs ORDER BY timestampMs DESC LIMIT 100")
  fun observeAll(): Flow<List<CallLogEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(log: CallLogEntity)

  @Query("DELETE FROM call_logs")
  suspend fun clearAll()
}

@Dao
interface ChatMessageDao {
  @Query("SELECT * FROM chat_messages ORDER BY timestampMs ASC")
  fun observeAllMessages(): Flow<List<ChatMessageEntity>>

  @Query("SELECT * FROM chat_messages WHERE peerId = :peerId ORDER BY timestampMs ASC")
  fun observeMessagesForPeer(peerId: String): Flow<List<ChatMessageEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(message: ChatMessageEntity): Long

  @Query("UPDATE chat_messages SET delivered = :delivered WHERE id = :id")
  suspend fun updateDelivered(id: Long, delivered: Boolean)

  @Query("DELETE FROM chat_messages WHERE peerId = :peerId")
  suspend fun deleteThread(peerId: String)

  @Query("DELETE FROM chat_messages")
  suspend fun clearAll()
}

@Database(
  entities = [CallLogEntity::class, ChatMessageEntity::class],
  version = 2,
  exportSchema = false,
)
abstract class NearCallDatabase : RoomDatabase() {
  abstract fun callLogDao(): CallLogDao
  abstract fun chatMessageDao(): ChatMessageDao

  companion object {
    @Volatile private var INSTANCE: NearCallDatabase? = null

    fun getInstance(context: Context): NearCallDatabase =
      INSTANCE
        ?: synchronized(this) {
          INSTANCE
            ?: Room.databaseBuilder(
                context.applicationContext,
                NearCallDatabase::class.java,
                "fjradio_offline.db",
              )
              .fallbackToDestructiveMigration(dropAllTables = true)
              .build()
              .also { INSTANCE = it }
        }
  }
}
