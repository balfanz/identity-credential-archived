package com.android.identity_credential.wallet

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.identity.android.securearea.AndroidKeystoreKeyInfo
import com.android.identity.android.securearea.AndroidKeystoreSecureArea
import com.android.identity.android.securearea.UserAuthenticationType
import com.android.identity.cbor.Cbor
import com.android.identity.cose.Cose
import com.android.identity.cose.CoseNumberLabel
import com.android.identity.credential.AuthenticationKey
import com.android.identity.credential.Credential
import com.android.identity.credential.CredentialStore
import com.android.identity.credentialtype.CredentialTypeRepository
import com.android.identity.issuance.CredentialCondition
import com.android.identity.issuance.CredentialExtensions.credentialConfiguration
import com.android.identity.issuance.CredentialExtensions.credentialIdentifier
import com.android.identity.issuance.CredentialExtensions.housekeeping
import com.android.identity.issuance.CredentialExtensions.issuingAuthorityIdentifier
import com.android.identity.issuance.CredentialExtensions.state
import com.android.identity.issuance.IssuingAuthority
import com.android.identity.issuance.IssuingAuthorityRepository
import com.android.identity.mdoc.mso.MobileSecurityObjectParser
import com.android.identity.mdoc.mso.StaticAuthDataParser
import com.android.identity.securearea.SecureAreaRepository
import com.android.identity.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant

class CardViewModel : ViewModel() {

    val cards = mutableStateListOf<Card>()

    private lateinit var context: Context
    private lateinit var credentialStore: CredentialStore
    private lateinit var issuingAuthorityRepository: IssuingAuthorityRepository
    private lateinit var secureAreaRepository: SecureAreaRepository
    private lateinit var credentialTypeRepository: CredentialTypeRepository

    fun getCard(cardId: String): Card? {
        for (card in cards) {
            if (card.id == cardId) {
                return card
            }
        }
        return null
    }

    fun refreshCard(card: Card) {
        val credential = credentialStore.lookupCredential(card.id)
        if (credential == null) {
            Logger.w(TAG, "No credential with id ${card.id}")
            return
        }
        refreshCredential(credential, true, true)
    }

    val refreshCredentialMutex = Mutex()

    private fun refreshCredential(
        credential: Credential,
        forceUpdate: Boolean,
        showFeedback: Boolean
    ) {
        // For now we run the entire housekeeping sequence in response to
        // receiving an update from the issuer... this could be an user
        // preference or for some events - such as new PII - we could pop
        // up a notification asking if the user would like to update their
        // credential
        //
        viewModelScope.launch(Dispatchers.IO) {
            // Especially during provisioning it's not uncommon to receive multiple
            // onCredentialStageChanged after each other... this ensures that we're
            // only running housekeeping() on a single credential at a time.
            //
            // TODO: this can be done more elegantly
            //
            refreshCredentialMutex.withLock {
                val numAuthKeysRefreshed = credential.housekeeping(
                    issuingAuthorityRepository,
                    secureAreaRepository,
                    forceUpdate,
                )
                if (showFeedback) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            context,
                            String.format(
                                context.resources.getQuantityString(R.plurals.refreshed_authkey, numAuthKeysRefreshed),
                                numAuthKeysRefreshed
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    fun deleteCard(card: Card) {
        val credential = credentialStore.lookupCredential(card.id)
        if (credential == null) {
            Logger.w(TAG, "No credential with id ${card.id}")
            return
        }
        credentialStore.deleteCredential(credential.name)
    }

    private val credentialStoreObserver = object : CredentialStore.Observer {
        override fun onCredentialAdded(credential: Credential) {
            addCredential(credential)
        }
        
        override fun onCredentialDeleted(credential: Credential) {
            removeCredential(credential)
        }
        
        override fun onCredentialChanged(credential: Credential) {
            updateCredential(credential)
        }
    }

    private val issuingAuthorityRepositoryObserver = object : IssuingAuthorityRepository.Observer {
        override fun onCredentialStateChanged(
            issuingAuthority: IssuingAuthority,
            credentialId: String
        ) {
            // Find the local [Credential] instance, if any
            for (id in credentialStore.listCredentials()) {
                val credential = credentialStore.lookupCredential(id)
                if (credential == null) {
                    continue
                }
                if (credential.issuingAuthorityIdentifier == issuingAuthority.configuration.identifier &&
                            credential.credentialIdentifier == credentialId) {

                    Logger.i(TAG, "Handling CredentialStateChanged on $credentialId")

                    refreshCredential(credential, true, false)
                }
            }
        }
    }

    private fun getStr(getStrId: Int): String {
        return context.resources.getString(getStrId)
    }

    private fun createCardForCredential(credential: Credential): Card {
        val credentialConfiguration = credential.credentialConfiguration
        val options = BitmapFactory.Options()
        options.inMutable = true
        val credentialBitmap = BitmapFactory.decodeByteArray(
            credentialConfiguration.cardArt,
            0,
            credentialConfiguration.cardArt.size,
            options
        )

        val issuer = issuingAuthorityRepository.lookupIssuingAuthority(credential.issuingAuthorityIdentifier)
        if (issuer == null) {
            Logger.w(TAG, "Unknown issuer ${credential.issuingAuthorityIdentifier} for " +
                "credential ${credential.name}")
        }

        val statusString =
            when (credential.state.condition) {
                CredentialCondition.PROOFING_REQUIRED -> getStr(R.string.card_view_model_status_proofing_required)
                CredentialCondition.PROOFING_PROCESSING -> getStr(R.string.card_view_model_status_proofing_processing)
                CredentialCondition.PROOFING_FAILED -> getStr(R.string.card_view_model_status_proofing_failed)
                CredentialCondition.CONFIGURATION_AVAILABLE -> getStr(R.string.card_view_model_status_configuration_available)
                CredentialCondition.READY -> getStr(R.string.card_view_model_status_ready)
                CredentialCondition.DELETION_REQUESTED -> getStr(R.string.card_view_model_status_deletion_requested)
            }

        val data = credential.getUserVisibleDetails(credentialTypeRepository)

        val keyInfos = mutableStateListOf<CardKeyInfo>()
        for (authKey in credential.authenticationKeys) {
            keyInfos.add(createCardInfoForAuthKey(authKey))
        }

        return Card(
            id = credential.name,
            name = credentialConfiguration.displayName,
            issuer = issuer?.configuration?.name ?: getStr(R.string.card_view_model_unknown_issuer),
            typeName = data.typeName ?: getStr(R.string.card_view_model_unknown_card_type),
            artwork = credentialBitmap,
            lastRefresh = Instant.fromEpochMilliseconds(credential.state.timestamp),
            status = statusString,
            attributes = data.attributes,
            attributePortrait = data.portrait,
            attributeSignatureOrUsualMark = data.signatureOrUsualMark,
            keyInfos = keyInfos,
        )
    }

    private fun createCardInfoForAuthKey(authKey: AuthenticationKey): CardKeyInfo {

        val credentialData = StaticAuthDataParser(authKey.issuerProvidedData).parse()
        val issuerAuthCoseSign1 = Cbor.decode(credentialData.issuerAuth).asCoseSign1
        val encodedMsoBytes = Cbor.decode(issuerAuthCoseSign1.payload!!)
        val encodedMso = Cbor.encode(encodedMsoBytes.asTaggedEncodedCbor)
        val mso = MobileSecurityObjectParser(encodedMso).parse()


        val kvPairs = mutableMapOf<String, String>()
        kvPairs.put("Document Type", mso.docType)
        kvPairs.put("MSO Version", mso.version)
        kvPairs.put("Issuer Data Digest Algorithm", mso.digestAlgorithm)

        val deviceKeyInfo = authKey.secureArea.getKeyInfo(authKey.alias)
        kvPairs.put("Device Key Curve", deviceKeyInfo.publicKey.curve.name)
        kvPairs.put("Secure Area", authKey.secureArea.displayName)

        if (deviceKeyInfo is AndroidKeystoreKeyInfo) {
            val userAuthString =
                if (!deviceKeyInfo.isUserAuthenticationRequired) {
                    "None"
                } else {
                    val authTimeoutString =
                        if (deviceKeyInfo.userAuthenticationTimeoutMillis > 0) {
                            String.format(
                                "Timeout %.1f Seconds",
                                deviceKeyInfo.userAuthenticationTimeoutMillis / 1000
                            )
                        } else {
                            "Every use"
                        }
                    deviceKeyInfo.userAuthenticationTypes.toString() + " ($authTimeoutString)"
                }
            kvPairs.put("User Authentication", userAuthString)
            val isStrongBoxBacked =
                if (deviceKeyInfo.isStrongBoxBacked) {
                    "Yes"
                } else {
                    "No"
                }
            kvPairs.put("In StrongBox", isStrongBoxBacked)
        }

        kvPairs.put("Issuer Provided Data", "${authKey.issuerProvidedData.size} bytes")


        return CardKeyInfo(
            description = "ISO/IEC 18013-5:2021 mdoc MSO",
            usageCount = authKey.usageCount,
            signedAt = Instant.fromEpochMilliseconds(mso.signed.toEpochMilli()),
            validFrom = Instant.fromEpochMilliseconds(mso.validFrom.toEpochMilli()),
            validUntil = Instant.fromEpochMilliseconds(mso.validUntil.toEpochMilli()),
            expectedUpdate = mso.expectedUpdate?.let {
                Instant.fromEpochMilliseconds(it.toEpochMilli())
            },
            replacementPending = authKey.replacement != null,
            details = kvPairs
        )
    }

    private fun addCredential(credential: Credential) {
        cards.add(createCardForCredential(credential))
    }

    private fun removeCredential(credential: Credential) {
        val cardIndex = cards.indexOfFirst { it.id == credential.name }
        if (cardIndex < 0) {
            Logger.w(TAG, "No card for credential with id ${credential.name}")
            return
        }
        cards.removeAt(cardIndex)
    }

    private fun updateCredential(credential: Credential) {
        val cardIndex = cards.indexOfFirst { it.id == credential.name }
        if (cardIndex < 0) {
            Logger.w(TAG, "No card for credential with id ${credential.name}")
            return
        }
        cards[cardIndex] = createCardForCredential(credential)
    }

    fun setData(
        context: Context,
        credentialStore: CredentialStore,
        issuingAuthorityRepository: IssuingAuthorityRepository,
        secureAreaRepository: SecureAreaRepository,
        credentialTypeRepository: CredentialTypeRepository
    ) {
        this.context = context
        this.credentialStore = credentialStore
        this.issuingAuthorityRepository = issuingAuthorityRepository
        this.secureAreaRepository = secureAreaRepository
        this.credentialTypeRepository = credentialTypeRepository

        credentialStore.startObserving(credentialStoreObserver)
        issuingAuthorityRepository.startObserving(issuingAuthorityRepositoryObserver)

        for (credentialId in credentialStore.listCredentials()) {
            val credential = credentialStore.lookupCredential(credentialId)!!
            addCredential(credential)
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (this::credentialStore.isInitialized) {
            credentialStore.stopObserving(credentialStoreObserver)
            issuingAuthorityRepository.stopObserving(issuingAuthorityRepositoryObserver)
        }
    }


    companion object {
        const val TAG = "CardViewModel"
    }

}