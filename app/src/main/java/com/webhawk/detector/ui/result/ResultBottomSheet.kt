package com.webhawk.detector.ui.result

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.webhawk.detector.R
import com.webhawk.detector.data.model.RiskResult
import com.webhawk.detector.data.model.RiskResult.RiskLevel
import com.webhawk.detector.databinding.BottomSheetResultBinding
import com.webhawk.detector.ui.result.RedirectChainAdapter

class ResultBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "ResultBottomSheet"
        private const val ARG_RESULT = "risk_result"
        private const val ARG_WAS_FLAGGED = "was_flagged"
        private const val ARG_IS_LOGGED_IN = "is_logged_in"

        fun newInstance(
            result: RiskResult,
            wasFlagged: Boolean,
            isLoggedIn: Boolean
        ) = ResultBottomSheet().apply {
            arguments = bundleOf(
                ARG_RESULT to result,
                ARG_WAS_FLAGGED to wasFlagged,
                ARG_IS_LOGGED_IN to isLoggedIn
            )
        }
    }

    private var _binding: BottomSheetResultBinding? = null
    private val binding get() = _binding!!

    var onFlagClick: (() -> Unit)? = null
    var onLoginToFlagClick: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetResultBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val result = requireArguments().getParcelable<RiskResult>(ARG_RESULT)
            ?: run { dismiss(); return }
        val wasFlagged = requireArguments().getBoolean(ARG_WAS_FLAGGED)
        val isLoggedIn = requireArguments().getBoolean(ARG_IS_LOGGED_IN)

        renderResult(result, wasFlagged, isLoggedIn)
    }

    private fun renderResult(result: RiskResult, wasFlagged: Boolean, isLoggedIn: Boolean) {
        // Risk meter
        val (colorRes, labelText, iconRes) = when (result.riskLevel) {
            RiskLevel.SAFE     -> Triple(R.color.risk_safe,     "SAFE",     R.drawable.ic_shield_check)
            RiskLevel.LOW      -> Triple(R.color.risk_low,      "LOW",      R.drawable.ic_shield_alert)
            RiskLevel.MEDIUM   -> Triple(R.color.risk_medium,   "MEDIUM",   R.drawable.ic_shield_alert)
            RiskLevel.HIGH     -> Triple(R.color.risk_high,     "HIGH",     R.drawable.ic_shield_off)
            RiskLevel.CRITICAL -> Triple(R.color.risk_critical, "CRITICAL", R.drawable.ic_shield_off)
        }

        val riskColor = ContextCompat.getColor(requireContext(), colorRes)
        binding.tvRiskLevel.text = labelText
        binding.tvRiskLevel.setTextColor(riskColor)
        binding.ivRiskIcon.setImageResource(iconRes)
        binding.ivRiskIcon.setColorFilter(riskColor)
        binding.tvFinalScore.text = String.format("%.2f", result.finalRisk)
        binding.tvFinalScore.setTextColor(riskColor)

        // Score breakdown
        binding.tvLocalScore.text = String.format("%.2f", result.localScore)
        binding.tvGlobalScore.text = String.format("%.2f", result.globalReputation)
        binding.tvRedirectCount.text = result.features.redirectCount.toString()
        binding.tvUniqueDomains.text = result.features.uniqueDomains.toString()
        binding.tvShortener.text = if (result.features.hasShortener) "Yes" else "No"
        binding.tvSuspiciousTld.text = if (result.features.hasSuspiciousTld) "Yes" else "No"

        // Explanation bullets
        val explanationText = result.explanation.joinToString("\n") { "• $it" }
        binding.tvExplanation.text = explanationText

        // Redirect chain
        if (result.features.chain.entries.isNotEmpty()) {
            binding.rvChain.adapter = RedirectChainAdapter(result.features.chain.entries)
            binding.cardChain.isVisible = true
        } else {
            binding.cardChain.isVisible = false
        }

        // Community flag badge
        binding.tvCommunityFlagged.isVisible = wasFlagged

        // Flag button
        binding.btnFlag.isVisible = isLoggedIn
        binding.tvLoginToFlag.isVisible = !isLoggedIn
        binding.tvLoginToFlag.setOnClickListener {
            onLoginToFlagClick?.invoke()
            dismiss()
        }
        binding.btnFlag.setOnClickListener {
            onFlagClick?.invoke()
            binding.btnFlag.isEnabled = false
            binding.btnFlag.text = getString(R.string.flagged)
            Toast.makeText(requireContext(), "URL flagged — thank you!", Toast.LENGTH_SHORT).show()
        }

        // Dismiss
        binding.btnDismiss.setOnClickListener { dismiss() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
