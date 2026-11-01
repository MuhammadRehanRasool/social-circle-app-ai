package dev.braindump.splitter.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.braindump.splitter.data.ai.embedding.MediaPipeTextEmbeddingEngine
import dev.braindump.splitter.data.ai.embedding.TextEmbeddingEngine
import dev.braindump.splitter.data.local.BrainDumpDatabase
import dev.braindump.splitter.data.local.IdeaDao
import dev.braindump.splitter.data.local.crm.CaptureDao
import dev.braindump.splitter.data.local.crm.ConnectionDao
import dev.braindump.splitter.data.local.crm.InteractionLogDao
import dev.braindump.splitter.data.local.crm.PersonDao
import dev.braindump.splitter.data.repository.IdeaRepositoryImpl
import dev.braindump.splitter.data.repository.PeopleRepositoryImpl
import dev.braindump.splitter.domain.repository.IdeaRepository
import dev.braindump.splitter.domain.repository.PeopleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Scope for writes that must finish even after the screen that started them is gone. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BrainDumpDatabase =
        Room.databaseBuilder(context, BrainDumpDatabase::class.java, BrainDumpDatabase.NAME)
            .addMigrations(
                BrainDumpDatabase.MIGRATION_1_2,
                BrainDumpDatabase.MIGRATION_2_3,
                BrainDumpDatabase.MIGRATION_3_4,
                BrainDumpDatabase.MIGRATION_4_5,
            )
            .addCallback(BrainDumpDatabase.SECURE_DELETE)
            .build()

    @Provides
    fun provideIdeaDao(database: BrainDumpDatabase): IdeaDao = database.ideaDao()

    @Provides
    fun providePersonDao(database: BrainDumpDatabase): PersonDao = database.personDao()

    @Provides
    fun provideCaptureDao(database: BrainDumpDatabase): CaptureDao = database.captureDao()

    @Provides
    fun provideInteractionLogDao(database: BrainDumpDatabase): InteractionLogDao = database.interactionLogDao()

    @Provides
    fun provideConnectionDao(database: BrainDumpDatabase): ConnectionDao = database.connectionDao()

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindIdeaRepository(impl: IdeaRepositoryImpl): IdeaRepository

    @Binds
    @Singleton
    abstract fun bindPeopleRepository(impl: PeopleRepositoryImpl): PeopleRepository

    @Binds
    @Singleton
    abstract fun bindTextEmbeddingEngine(impl: MediaPipeTextEmbeddingEngine): TextEmbeddingEngine
}
