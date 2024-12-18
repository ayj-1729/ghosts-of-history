/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ghosts.of.history.view

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.ghosts.of.history.R

/** A DialogFragment for the Privacy Notice Dialog Box.  */
class PrivacyNoticeDialogFragment : DialogFragment() {
    /** Listener for weather to start a host or resolve operation.  */
    fun interface HostResolveListener {
        /** Invoked when the user accepts sharing experience.  */
        fun onPrivacyNoticeReceived()
    }

    var hostResolveListener: HostResolveListener? = null
    override fun onDetach() {
        super.onDetach()
        hostResolveListener = null
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(activity)
        builder
                .setTitle(R.string.share_experience_title)
                .setMessage(R.string.share_experience_message)
                .setPositiveButton(
                        R.string.agree_to_share
                ) { _, _ -> // Send the positive button event back to the host activity
                    hostResolveListener!!.onPrivacyNoticeReceived()
                }
                .setNegativeButton(
                        R.string.learn_more
                ) { _, _ ->
                    val browserIntent = Intent(
                            Intent.ACTION_VIEW, Uri.parse(getString(R.string.learn_more_url)))
                    requireActivity().startActivity(browserIntent)
                }
        return builder.create()
    }

    companion object {
        fun createDialog(hostResolveListener: HostResolveListener?): PrivacyNoticeDialogFragment {
            val dialogFragment = PrivacyNoticeDialogFragment()
            dialogFragment.hostResolveListener = hostResolveListener
            return dialogFragment
        }
    }
}