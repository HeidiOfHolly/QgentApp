package com.example.qgent.di

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.qgent.data.api.RetrofitClient
import com.example.qgent.data.repository.AgentRepository
import com.example.qgent.data.repository.AgentRepositoryImpl
import com.example.qgent.data.repository.AuthRepository
import com.example.qgent.data.repository.ChatRepository
import com.example.qgent.data.repository.ChatRepositoryImpl
import com.example.qgent.data.repository.FallbackAgentRepository
import com.example.qgent.data.repository.FallbackChatRepository
import com.example.qgent.data.repository.FallbackGitHubRepository
import com.example.qgent.data.repository.FallbackUserRepository
import com.example.qgent.data.repository.GitHubRepository
import com.example.qgent.data.repository.GitHubRepositoryImpl
import com.example.qgent.data.repository.MockAgentRepository
import com.example.qgent.data.repository.MockChatRepository
import com.example.qgent.data.repository.MockGitHubRepository
import com.example.qgent.data.repository.MockUserRepository
import com.example.qgent.data.repository.UserRepository
import com.example.qgent.data.repository.UserRepositoryImpl
import com.example.qgent.ui.auth.AuthViewModel
import com.example.qgent.ui.github.GithubViewModel
import com.example.qgent.viewmodel.MainViewModel

/**
 * 手工 DI 容器：由 QgentApp 持有，统一装配数据/网络层与 ViewModel Factory。
 *
 * Repository 采用「真实 → mock 回退」组合，后端不可用时自动降级到演示数据。
 */
class AppContainer {

    val authRepository = AuthRepository(RetrofitClient.service)

    val userRepository: UserRepository = FallbackUserRepository(
        real = UserRepositoryImpl(RetrofitClient.service),
        mock = MockUserRepository()
    )

    val chatRepository: ChatRepository = FallbackChatRepository(
        real = ChatRepositoryImpl(RetrofitClient.service),
        mock = MockChatRepository()
    )

    val agentRepository: AgentRepository = FallbackAgentRepository(
        real = AgentRepositoryImpl(RetrofitClient.service),
        mock = MockAgentRepository()
    )

    val githubRepository: GitHubRepository = FallbackGitHubRepository(
        real = GitHubRepositoryImpl(RetrofitClient.service),
        mock = MockGitHubRepository()
    )

    val authViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer { AuthViewModel(authRepository) }
    }

    val mainViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer { MainViewModel(userRepository, chatRepository, agentRepository) }
    }

    val githubViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer { GithubViewModel(githubRepository) }
    }
}
