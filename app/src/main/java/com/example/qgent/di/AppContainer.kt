package com.example.qgent.di

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.qgent.data.api.RetrofitClient
import com.example.qgent.data.local.MessageCache
import com.example.qgent.data.local.QgentDatabase
import com.example.qgent.data.repository.AgentRepository
import com.example.qgent.data.repository.AgentRepositoryImpl
import com.example.qgent.data.repository.AttachmentUploader
import com.example.qgent.data.repository.AgentAvatarUploader
import com.example.qgent.data.repository.AvatarUploader
import com.example.qgent.data.repository.AuthRepository
import com.example.qgent.data.repository.ChatRepository
import com.example.qgent.data.repository.ChatRepositoryImpl
import com.example.qgent.data.repository.DiffRepository
import com.example.qgent.data.repository.DiffRepositoryImpl
import com.example.qgent.data.repository.GitHubRepository
import com.example.qgent.data.repository.GitHubRepositoryImpl
import com.example.qgent.data.repository.MemoryRepository
import com.example.qgent.data.repository.MemoryRepositoryImpl
import com.example.qgent.data.repository.SkillRepository
import com.example.qgent.data.repository.SkillRepositoryImpl
import com.example.qgent.data.repository.TaskRepository
import com.example.qgent.data.repository.TaskRepositoryImpl
import com.example.qgent.data.repository.UserRepository
import com.example.qgent.data.repository.UserRepositoryImpl
import com.example.qgent.data.sse.ProjectEventStream
import com.example.qgent.ui.auth.AuthViewModel
import com.example.qgent.ui.github.GithubViewModel
import com.example.qgent.ui.tasks.TaskListViewModel
import com.example.qgent.viewmodel.MainViewModel
import com.example.qgent.viewmodel.NewProjectViewModel

/**
 * 手工 DI 容器：由 QgentApp 持有，统一装配数据/网络层与 ViewModel Factory。
 *
 * 全部直接使用真实后端实现，不使用 mock 回退。
 */
class AppContainer(context: Context) {

    val database = QgentDatabase.getInstance(context)
    val messageCache = MessageCache(database.messageDao())
    val attachmentUploader = AttachmentUploader(RetrofitClient.service, RetrofitClient.uploadClient)
    val avatarUploader = AvatarUploader(RetrofitClient.service, RetrofitClient.uploadClient)
    val agentAvatarUploader = AgentAvatarUploader(RetrofitClient.service, RetrofitClient.uploadClient)

    val authRepository = AuthRepository(RetrofitClient.service)

    val userRepository: UserRepository = UserRepositoryImpl(RetrofitClient.service)

    val chatRepository: ChatRepository = ChatRepositoryImpl(RetrofitClient.service)

    /**
     * Agent：真实接口实现（mock 回退已移除，后端接口已就绪）。
     */
    val agentRepository: AgentRepository = AgentRepositoryImpl(RetrofitClient.service)

    /**
     * Skill / Memory / Diff：真实接口实现（Fallback/Mock 已移除，后端接口已就绪）。
     */
    val skillRepository: SkillRepository = SkillRepositoryImpl(RetrofitClient.service)

    val memoryRepository: MemoryRepository = MemoryRepositoryImpl(RetrofitClient.service)

    val diffRepository: DiffRepository = DiffRepositoryImpl(RetrofitClient.service)

    val githubRepository: GitHubRepository = GitHubRepositoryImpl(RetrofitClient.service)

    val taskRepository: TaskRepository = TaskRepositoryImpl(RetrofitClient.service)

    /**
     * 项目级 SSE 事件流（文档 §12.1）。
     * 复用带鉴权 + Token 自动刷新的 httpClient；连接生命周期由使用方（Fragment）控制。
     */
    val projectEventStream = ProjectEventStream(RetrofitClient.httpClient, RetrofitClient.BASE_URL)

    /**
     * WebSocket 实时通道（后端 2026-08-17 新增：单连接用户级聚合，SSE 保留兼容）。
     * 握手 ?token= 鉴权；聊天页实时刷新优先走它（规避 SSE 长连接被 CDN/网关掐断）；
     * 握手 401 时用 refresh token 换新 access token 自动重连。
     */
    val realtimeClient = com.example.qgent.data.ws.RealtimeClient(
        RetrofitClient.httpClient,
        RetrofitClient.BASE_URL,
        refreshAccessToken = { RetrofitClient.refreshAccessToken() }
    )

    val authViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer { AuthViewModel(authRepository) }
    }

    val mainViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer { MainViewModel(userRepository, chatRepository, agentRepository, githubRepository) }
    }

    val newProjectViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer { NewProjectViewModel(userRepository, githubRepository) }
    }

    val githubViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer { GithubViewModel(githubRepository) }
    }

    val taskListViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer { TaskListViewModel(taskRepository, githubRepository) }
    }
}
