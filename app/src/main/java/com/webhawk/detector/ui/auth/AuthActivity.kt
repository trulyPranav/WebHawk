package com.webhawk.detector.ui.auth

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.webhawk.detector.R
import com.webhawk.detector.databinding.ActivityAuthBinding
import kotlinx.coroutines.launch

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private val viewModel: AuthViewModel by viewModels()
    private var isLoginMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUi()
        observeState()
    }

    private fun setupUi() {
        updateMode()

        binding.btnToggleMode.setOnClickListener {
            isLoginMode = !isLoginMode
            updateMode()
            viewModel.resetState()
        }

        binding.btnSubmit.setOnClickListener {
            val email = binding.etEmail.text?.toString() ?: ""
            val password = binding.etPassword.text?.toString() ?: ""
            if (isLoginMode) {
                viewModel.login(email, password)
            } else {
                val name = binding.etDisplayName.text?.toString() ?: ""
                viewModel.register(email, password, name)
            }
        }

        binding.tvForgotPassword.setOnClickListener {
            val email = binding.etEmail.text?.toString() ?: ""
            viewModel.sendPasswordReset(email)
        }

        binding.ibBack.setOnClickListener { finish() }

        // Clear errors on text change
        listOf(binding.etEmail, binding.etPassword, binding.etDisplayName).forEach { et ->
            et.doAfterTextChanged { viewModel.resetState() }
        }
    }

    private fun updateMode() {
        if (isLoginMode) {
            binding.tvTitle.text = getString(R.string.sign_in)
            binding.btnSubmit.text = getString(R.string.sign_in)
            binding.btnToggleMode.text = getString(R.string.no_account_register)
            binding.tilDisplayName.visibility = View.GONE
            binding.tvForgotPassword.visibility = View.VISIBLE
        } else {
            binding.tvTitle.text = getString(R.string.create_account)
            binding.btnSubmit.text = getString(R.string.create_account)
            binding.btnToggleMode.text = getString(R.string.have_account_login)
            binding.tilDisplayName.visibility = View.VISIBLE
            binding.tvForgotPassword.visibility = View.GONE
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is AuthUiState.Idle -> setLoading(false)
                        is AuthUiState.Loading -> setLoading(true)
                        is AuthUiState.Success -> {
                            setLoading(false)
                            setResult(RESULT_OK)
                            finish()
                        }
                        is AuthUiState.Error -> {
                            setLoading(false)
                            binding.tvError.text = state.message
                            binding.tvError.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSubmit.isEnabled = !loading
        binding.btnToggleMode.isEnabled = !loading
        binding.tvError.visibility = View.GONE
    }
}
