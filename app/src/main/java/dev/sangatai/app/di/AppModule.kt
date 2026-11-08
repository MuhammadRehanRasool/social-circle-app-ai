package dev.sangatai.app.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.sangatai.app.data.ai.embedding.MediaPipeTextEmbeddingEngine
import dev.sangatai.app.data.ai.embedding.TextEmbeddingEngine
import dev.sangatai.app.data.local.SangatDatabase
import dev.sangatai.app.data.local.IdeaDao
import dev.sangatai.app.data.local.crm.CaptureDao
import dev.sangatai.app.data.local.crm.ConnectionDao
import dev.sangatai.app.data.local.crm.InteractionLogDao
import dev.sangatai.app.data.local.crm.PersonDao
import dev.sangatai.app.data.local.crm.RelationFactDao
import dev.sangatai.app.data.repository.PeopleRepositoryImpl
import dev.sangatai.app.domain.repository.PeopleRepository
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
    fun provideDatabase(@ApplicationContext context: Context): SangatDatabase =
        Room.databaseBuilder(context, SangatDatabase::class.java, SangatDatabase.NAME)
            .addMigrations(
                SangatDatabase.MIGRATION_1_2,
                SangatDatabase.MIGRATION_2_3,
                SangatDatabase.MIGRATION_3_4,
                SangatDatabase.MIGRATION_4_5,
                SangatDatabase.MIGRATION_5_6,
            )
            .addCallback(SangatDatabase.SECURE_DELETE)
            .build()

    @Provides
    fun provideIdeaDao(database: SangatDatabase): IdeaDao = database.ideaDao()

    @Provides
    fun providePersonDao(database: SangatDatabase): PersonDao = database.personDao()

    @Provides
    fun provideCaptureDao(database: SangatDatabase): CaptureDao = database.captureDao()

    @Provides
    fun provideInteractionLogDao(database: SangatDatabase): InteractionLogDao = database.interactionLogDao()

    @Provides
    fun provideConnectionDao(database: SangatDatabase): ConnectionDao = database.connectionDao()

    @Provides
    fun provideRelationFactDao(database: SangatDatabase): RelationFactDao = database.relationFactDao()

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
    abstract fun bindPeopleRepository(impl: PeopleRepositoryImpl): PeopleRepository

    @Binds
    @Singleton
    abstract fun bindTextEmbeddingEngine(impl: MediaPipeTextEmbeddingEngine): TextEmbeddingEngine
}
