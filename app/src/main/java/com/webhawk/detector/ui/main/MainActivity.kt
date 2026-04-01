package com.webhawk.detector.ui.main

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.webhawk.detector.R
import com.webhawk.detector.data.model.FlaggedUrl
import com.webhawk.detector.data.model.RiskResult
import com.webhawk.detector.data.model.RiskResult.RiskLevel
import com.webhawk.detector.databinding.ActivityMainBinding
import com.webhawk.detector.ui.auth.AuthActivity
import com.webhawk.detector.ui.result.ResultBottomSheet
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val authLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            updateUserChip()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUi()
        observeState()
        observeServiceState()
    }

    private fun setupUi() {
        updateUserChip()

        binding.btnScan.setOnClickListener {
            val url = binding.etUrl.text?.toString() ?: ""
            hideKeyboard()
            viewModel.scanUrl(url)
        }

        binding.etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                viewModel.scanUrl(binding.etUrl.text?.toString() ?: "")
                true
            } else false
        }

        binding.chipUser.setOnClickListener {
            if (viewModel.isLoggedIn) {
                showLogoutDialog()
            } else {
                authLauncher.launch(Intent(this, AuthActivity::class.java))
            }
        }

        binding.btnEnableService.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnStopScan.setOnClickListener {
            viewModel.stopTrackingAndAnalyze()
        }

        binding.btnReset.setOnClickListener {
            viewModel.reset()
            binding.etUrl.text?.clear()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.scanState.collect { state ->
                    handleScanState(state)
                }
            }
        }
    }

    private fun observeServiceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.serviceRunning.collect { running ->
                    binding.cardServiceWarning.isVisible = !running
                    binding.btnScan.isEnabled = running
                    binding.etUrl.isEnabled = running
                }
            }
        }
    }

    private fun handleScanState(state: ScanUiState) {
        // Reset all panels
        binding.cardStatus.isVisible = false
        binding.btnStopScan.isVisible = false
        binding.btnReset.isVisible = false

        when (state) {
            is ScanUiState.Idle -> { /* nothing — default clean state */ }

            is ScanUiState.CheckingDatabase -> {
                binding.cardStatus.isVisible = true
                binding.tvStatusLabel.text = getString(R.string.checking_database)
                binding.progressStatus.isVisible = true
            }

            is ScanUiState.Flagged -> {
                showFlaggedDialog(state.flaggedUrl)
            }

            is ScanUiState.Tracking -> {
                binding.cardStatus.isVisible = true
                binding.tvStatusLabel.text = getString(R.string.monitoring_redirects)
                binding.progressStatus.isVisible = true
                binding.btnStopScan.isVisible = true
            }

            is ScanUiState.Analyzing -> {
                binding.cardStatus.isVisible = true
                binding.tvStatusLabel.text = getString(R.string.analyzing)
                binding.progressStatus.isVisible = true
            }

            is ScanUiState.Result -> {
                binding.btnReset.isVisible = true
                showResultSheet(state.riskResult, state.wasFlagged)
            }

            is ScanUiState.Error -> {
                binding.btnReset.isVisible = true
                binding.cardStatus.isVisible = true
                binding.progressStatus.isVisible = false
                binding.tvStatusLabel.text = state.message
            }
        }
    }

    private fun showFlaggedDialog(flaggedUrl: FlaggedUrl) {
        AlertDialog.Builder(this, R.style.Theme_WebHawk_Dialog)
            .setTitle("⚠️ Flagged URL")
            .setMessage(
                "This URL has been flagged by the community ${flaggedUrl.flagCount} time(s).\n\n" +
                "Weighted risk score: ${String.format("%.1f", flaggedUrl.weightedScore)}\n\n" +
                "Do you want to continue scanning it anyway?"
            )
            .setPositiveButton("Continue Anyway") { _, _ ->
                viewModel.continueWithFlaggedUrl()
            }
            .setNegativeButton("Leave") { dialog, _ ->
                dialog.dismiss()
                viewModel.reset()
            }
            .setCancelable(false)
            .show()
    }

    private fun showResultSheet(result: RiskResult, wasFlagged: Boolean) {
        val sheet = ResultBottomSheet.newInstance(result, wasFlagged, viewModel.isLoggedIn)
        sheet.onFlagClick = {
            viewModel.flagCurrentUrl()
        }
        sheet.onLoginToFlagClick = {
            authLauncher.launch(Intent(this, AuthActivity::class.java))
        }
        sheet.show(supportFragmentManager, ResultBottomSheet.TAG)
    }

    private fun updateUserChip() {
        if (viewModel.isLoggedIn) {
            binding.chipUser.text = getString(R.string.logged_in)
            binding.chipUser.setChipIconResource(R.drawable.ic_account)
        } else {
            binding.chipUser.text = getString(R.string.sign_in_to_flag)
            binding.chipUser.setChipIconResource(R.drawable.ic_account_outline)
        }
    }

    private fun showLogoutDialog() {
        AlertDialog.Builder(this, R.style.Theme_WebHawk_Dialog)
            .setTitle("Sign out")
            .setMessage("Are you sure you want to sign out?")
            .setPositiveButton("Sign out") { _, _ ->
                (application as com.webhawk.detector.WebHawkApp).authRepository.logout()
                updateUserChip()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }
}
