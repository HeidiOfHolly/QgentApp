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
import com.example.qgent.data.repository.GitHubRepository
import com.example.qgent.data.repository.GitHubRepositoryImpl
import com.example.qgent.data.repository.UserRepository
import com.example.qgent.data.repository.UserRepositoryImpl
import com.example.qgent.ui.auth.AuthViewModel
import com.example.qgent.ui.github.GithubViewModel
import com.example.qgent.viewmodel.MainViewModel
import com.example.qgent.viewmodel.NewProjectViewModel

/**
 * 手工 DI 容器：由 QgentApp 持有，统一装配数据/网络层与 ViewModel Factory。
 *
 * 全部直接使用真实后端实现，不使用 mock 回退。
 */
class AppContainer {

    val authRepository = AuthRepository(RetrofitClient.service)

    val userRepository: UserRepository = UserRepositoryImpl(RetrofitClient.service)

    val chatRepository: ChatRepository = ChatRepositoryImpl(RetrofitClient.service)

    val agentRepository: AgentRepository = AgentRepositoryImpl(RetrofitClient.service)

    val githubRepository: GitHubRepository = GitHubRepositoryImpl(RetrofitClient.service)

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
}
