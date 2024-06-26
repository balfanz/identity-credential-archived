package com.android.identity_credential.wallet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.android.identity.cbor.Cbor
import com.android.identity.cbor.CborArray
import com.android.identity.cbor.CborMap
import com.android.identity.cbor.toDataItemDateTimeString
import com.android.identity.cbor.toDataItemFullDate
import com.android.identity.crypto.EcCurve
import com.android.identity.document.NameSpacedData
import com.android.identity.documenttype.DocumentType
import com.android.identity.documenttype.knowntypes.DrivingLicense
import com.android.identity.issuance.CredentialConfiguration
import com.android.identity.issuance.DocumentConfiguration
import com.android.identity.issuance.CredentialFormat
import com.android.identity.issuance.RegistrationResponse
import com.android.identity.issuance.IssuingAuthorityConfiguration
import com.android.identity.issuance.evidence.EvidenceRequestCreatePassphrase
import com.android.identity.issuance.evidence.EvidenceResponse
import com.android.identity.issuance.evidence.EvidenceResponseCreatePassphrase
import com.android.identity.issuance.evidence.EvidenceResponseIcaoNfcTunnelResult
import com.android.identity.issuance.evidence.EvidenceResponseIcaoPassiveAuthentication
import com.android.identity.issuance.evidence.EvidenceResponseMessage
import com.android.identity.issuance.evidence.EvidenceResponseQuestionMultipleChoice
import com.android.identity.issuance.simple.SimpleIcaoNfcTunnelDriver
import com.android.identity.issuance.simple.SimpleIssuingAuthorityProofingGraph
import com.android.identity.securearea.KeyPurpose
import com.android.identity.securearea.PassphraseConstraints
import com.android.identity.securearea.toDataItem
import com.android.identity.storage.StorageEngine
import com.android.identity_credential.mrtd.MrtdAccessData
import com.android.identity_credential.mrtd.MrtdNfcData
import com.android.identity_credential.mrtd.MrtdNfcDataDecoder
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.days

class SelfSignedMdlIssuingAuthority(
    application: WalletApplication,
    storageEngine: StorageEngine
) : SelfSignedMdocIssuingAuthority(application, storageEngine) {

    companion object {
        private const val MDL_DOCTYPE = DrivingLicense.MDL_DOCTYPE
        private const val MDL_NAMESPACE = DrivingLicense.MDL_NAMESPACE
        private const val AAMVA_NAMESPACE = DrivingLicense.AAMVA_NAMESPACE
    }

    override val docType: String = MDL_DOCTYPE
    override lateinit var configuration: IssuingAuthorityConfiguration
    private val tosAssets: Map<String, ByteArray>;

    init {
        val baos = ByteArrayOutputStream()
        BitmapFactory.decodeResource(
            application.applicationContext.resources, R.drawable.utopia_dmv_issuing_authority_logo
        ).compress(Bitmap.CompressFormat.PNG, 90, baos)
        val icon: ByteArray = baos.toByteArray()
        configuration = IssuingAuthorityConfiguration(
            identifier = "mDL_Utopia",
            issuingAuthorityName = resourceString(R.string.utopia_mdl_issuing_authority_name),
            issuingAuthorityLogo = icon,
            description = resourceString(R.string.utopia_mdl_issuing_authority_description),
            documentFormats = setOf(CredentialFormat.MDOC_MSO),
            pendingDocumentInformation = createDocumentConfiguration(null)
        )
        tosAssets = mapOf("utopia_logo.png" to resourceBytes(R.drawable.utopia_dmv_issuing_authority_logo))
    }

    override fun getProofingGraphRoot(
        registrationResponse: RegistrationResponse,
    ): SimpleIssuingAuthorityProofingGraph.Node {
        return SimpleIssuingAuthorityProofingGraph.create {
            message(
                "tos",
                resourceString(R.string.utopia_mdl_issuing_authority_tos),
                tosAssets,
                resourceString(R.string.utopia_mdl_issuing_authority_accept),
                resourceString(R.string.utopia_mdl_issuing_authority_reject),
            )
            choice(
                id = "path",
                message = resourceString(R.string.utopia_mdl_issuing_authority_hardcoded_or_derived),
                assets = mapOf(),
                acceptButtonText = "Continue"
            ) {
                on(id = "hardcoded", text = resourceString(R.string.utopia_mdl_issuing_authority_hardcoded_option)) {
                }
                on(id = "passport", text = resourceString(R.string.utopia_mdl_issuing_authority_passport_option)) {
                    icaoTunnel("tunnel", listOf(1, 2, 7), true) {
                        whenChipAuthenticated {}
                        whenActiveAuthenticated {}
                        whenNotAuthenticated {}
                    }
                }
            }
            if (registrationResponse.developerModeEnabled) {
                message(
                    "devmode_message",
                    message = "The following screens appear only because Developer Mode is enabled",
                    assets = mapOf(),
                    acceptButtonText = resourceString(R.string.utopia_mdl_issuing_authority_continue),
                    null
                )
                choice(
                    id = "devmode_sa",
                    message = "Choose Secure Area",
                    assets = mapOf(),
                    acceptButtonText = "Continue"
                ) {
                    on(id = "devmode_sa_android", text = "Android Keystore") {
                        choice(
                            id = "devmode_sa_android_use_strongbox",
                            message = "Use StrongBox",
                            assets = mapOf(),
                            acceptButtonText = "Continue"
                        ) {
                            on(id = "devmode_sa_android_use_strongbox_no", text = "Don't use StrongBox") {}
                            on(id = "devmode_sa_android_use_strongbox_yes", text = "Use StrongBox") {}
                        }
                        choice(
                            id = "devmode_sa_android_user_auth",
                            message = "Choose user authentication",
                            assets = mapOf(),
                            acceptButtonText = "Continue"
                        ) {
                            on(id = "devmode_sa_android_user_auth_lskf_biometrics", text = "LSKF or Biometrics") {}
                            on(id = "devmode_sa_android_user_auth_lskf", text = "Only LSKF") {}
                            on(id = "devmode_sa_android_user_auth_biometrics", text = "Only Biometrics") {}
                            on(id = "devmode_sa_android_user_auth_none", text = "None") {}
                        }
                        choice(
                            id = "devmode_sa_android_mdoc_auth",
                            message = "Choose mdoc authentication mode and EC curve",
                            assets = mapOf(),
                            acceptButtonText = "Continue"
                        ) {
                            on(id = "devmode_sa_android_mdoc_auth_ecdsa_p256", text = "ECDSA w/ P-256") {}
                            on(id = "devmode_sa_android_mdoc_auth_ed25519", text = "EdDSA w/ Ed25519") {}
                            on(id = "devmode_sa_android_mdoc_auth_ed448", text = "EdDSA w/ Ed448") {}
                            on(id = "devmode_sa_android_mdoc_auth_ecdh_p256", text = "ECDH w/ P-256") {}
                            on(id = "devmode_sa_android_mdoc_auth_x25519", text = "XDH w/ X25519") {}
                            on(id = "devmode_sa_android_mdoc_auth_x448", text = "XDH w/ X448") {}
                        }
                    }

                    on(id = "devmode_sa_software", text = "Software") {
                        choice(
                            id = "devmode_sa_software_passphrase_complexity",
                            message = "Choose what kind of passphrase to use",
                            assets = mapOf(),
                            acceptButtonText = "Continue"
                        ) {
                            on(id = "devmode_sa_software_passphrase_6_digit_pin", text = "6-digit PIN") {
                                createPassphrase(
                                    "devmode_sa_software_passphrase",
                                    message = "## Choose 6-digit PIN\n\nChoose the PIN to use for the document.\n\nThis is asked every time the document is presented so make sure you memorize it and don't share it with anyone else.",
                                    verifyMessage = "## Verify PIN\n\nEnter the PIN you chose in the previous screen.",
                                    assets = mapOf(),
                                    PassphraseConstraints.PIN_SIX_DIGITS
                                )
                            }
                            on(id = "devmode_sa_software_passphrase_8_char_or_longer_passphrase", text = "Passphrase 8 chars or longer") {
                                createPassphrase(
                                    "devmode_sa_software_passphrase",
                                    message = "## Choose passphrase\n\nChoose the passphrase to use for the document.\n\nThis is asked every time the document is presented so make sure you memorize it and don't share it with anyone else.",
                                    verifyMessage = "## Verify passphrase\n\nEnter the passphrase you chose in the previous screen.",
                                    assets = mapOf(),
                                    PassphraseConstraints(8, Int.MAX_VALUE, false)
                                )
                            }
                            on(id = "devmode_sa_software_passphrase_none", text = "None") {}
                        }
                        choice(
                            id = "devmode_sa_software_mdoc_auth",
                            message = "Choose mdoc authentication mode and EC curve",
                            assets = mapOf(),
                            acceptButtonText = "Continue"
                        ) {
                            on(id = "devmode_sa_software_mdoc_auth_ecdsa_p256", text = "ECDSA w/ P-256") {}
                            on(id = "devmode_sa_software_mdoc_auth_ecdsa_p384", text = "ECDSA w/ P-384") {}
                            on(id = "devmode_sa_software_mdoc_auth_ecdsa_p521", text = "ECDSA w/ P-521") {}
                            on(id = "devmode_sa_software_mdoc_auth_ecdsa_brainpoolp256r1", text = "ECDSA w/ brainpoolP256r1") {}
                            on(id = "devmode_sa_software_mdoc_auth_ecdsa_brainpoolp320r1", text = "ECDSA w/ brainpoolP320r1") {}
                            on(id = "devmode_sa_software_mdoc_auth_ecdsa_brainpoolp384r1", text = "ECDSA w/ brainpoolP384r1") {}
                            on(id = "devmode_sa_software_mdoc_auth_ecdsa_brainpoolp512r1", text = "ECDSA w/ brainpoolP512r1") {}
                            on(id = "devmode_sa_software_mdoc_auth_ed25519", text = "EdDSA w/ Ed25519") {}
                            on(id = "devmode_sa_software_mdoc_auth_ed448", text = "EdDSA w/ Ed448") {}
                            on(id = "devmode_sa_software_mdoc_auth_ecdh_p256", text = "ECDH w/ P-256") {}
                            on(id = "devmode_sa_software_mdoc_auth_ecdh_p384", text = "ECDH w/ P-384") {}
                            on(id = "devmode_sa_software_mdoc_auth_ecdh_p521", text = "ECDH w/ P-521") {}
                            on(id = "devmode_sa_software_mdoc_auth_ecdh_brainpoolp256r1", text = "ECDH w/ brainpoolP256r1") {}
                            on(id = "devmode_sa_software_mdoc_auth_ecdh_brainpoolp320r1", text = "ECDH w/ brainpoolP320r1") {}
                            on(id = "devmode_sa_software_mdoc_auth_ecdh_brainpoolp384r1", text = "ECDH w/ brainpoolP384r1") {}
                            on(id = "devmode_sa_software_mdoc_auth_ecdh_brainpoolp512r1", text = "ECDH w/ brainpoolP512r1") {}
                            on(id = "devmode_sa_software_mdoc_auth_x25519", text = "XDH w/ X25519") {}
                            on(id = "devmode_sa_software_mdoc_auth_x448", text = "XDH w/ X448") {}
                        }
                    }
                }
            }
            message(
                "message",
                message = resourceString(R.string.utopia_mdl_issuing_authority_application_finish),
                assets = mapOf(),
                acceptButtonText = resourceString(R.string.utopia_mdl_issuing_authority_continue),
                null
            )
            requestNotificationPermission(
                "notificationPermission",
                permissionNotAvailableMessage = resourceString(R.string.permission_post_notifications_rationale_md),
                grantPermissionButtonText = resourceString(R.string.permission_post_notifications_grant_permission_button_text),
                continueWithoutPermissionButtonText = resourceString(R.string.permission_post_notifications_continue_without_permission_button_text),
                assets = mapOf()
            )
        }
    }

    override fun getMrtdAccessData(collectedEvidence: Map<String, EvidenceResponse>): MrtdAccessData? {
        return null
    }

    override fun createNfcTunnelHandler(): SimpleIcaoNfcTunnelDriver {
        return NfcTunnelDriver()
    }

    override fun checkEvidence(collectedEvidence: Map<String, EvidenceResponse>): Boolean {
        return (collectedEvidence["tos"] as EvidenceResponseMessage).acknowledged
    }

    override fun generateDocumentConfiguration(collectedEvidence: Map<String, EvidenceResponse>): DocumentConfiguration {
        return createDocumentConfiguration(collectedEvidence)
    }

    override fun createCredentialConfiguration(
        collectedEvidence: MutableMap<String, EvidenceResponse>
    ): CredentialConfiguration {
        val challenge = byteArrayOf(1, 2, 3)
        if (!collectedEvidence.containsKey("devmode_sa")) {
            return CredentialConfiguration(
                challenge,
                "AndroidKeystoreSecureArea",
                Cbor.encode(
                    CborMap.builder()
                        .put("curve", EcCurve.P256.coseCurveIdentifier)
                        .put("purposes", KeyPurpose.encodeSet(setOf(KeyPurpose.SIGN)))
                        .put("userAuthenticationRequired", true)
                        .put("userAuthenticationTimeoutMillis", 0L)
                        .put("userAuthenticationTypes", 3 /* LSKF + Biometrics */)
                        .end().build()
                )
            )
        }

        val chosenSa = (collectedEvidence["devmode_sa"] as EvidenceResponseQuestionMultipleChoice).answerId
        when (chosenSa) {
            "devmode_sa_android" -> {
                val useStrongBox = when ((collectedEvidence["devmode_sa_android_use_strongbox"]
                        as EvidenceResponseQuestionMultipleChoice).answerId) {
                    "devmode_sa_android_use_strongbox_yes" -> true
                    "devmode_sa_android_use_strongbox_no" -> false
                    else -> throw IllegalStateException()
                }
                val userAuthType = when ((collectedEvidence["devmode_sa_android_user_auth"]
                        as EvidenceResponseQuestionMultipleChoice).answerId) {
                    "devmode_sa_android_user_auth_lskf_biometrics" -> 3
                    "devmode_sa_android_user_auth_lskf" -> 1
                    "devmode_sa_android_user_auth_biometrics" -> 2
                    "devmode_sa_android_user_auth_none" -> 0
                    else -> throw IllegalStateException()
                }
                val (curve, purposes) = when ((collectedEvidence["devmode_sa_android_mdoc_auth"]
                        as EvidenceResponseQuestionMultipleChoice).answerId) {
                    "devmode_sa_android_mdoc_auth_ecdsa_p256" -> Pair(EcCurve.P256, setOf(KeyPurpose.SIGN))
                    "devmode_sa_android_mdoc_auth_ed25519" -> Pair(EcCurve.ED25519, setOf(KeyPurpose.SIGN))
                    "devmode_sa_android_mdoc_auth_ed448" -> Pair(EcCurve.ED448, setOf(KeyPurpose.SIGN))
                    "devmode_sa_android_mdoc_auth_ecdh_p256" -> Pair(EcCurve.P256, setOf(KeyPurpose.AGREE_KEY))
                    "devmode_sa_android_mdoc_auth_x25519" -> Pair(EcCurve.X25519, setOf(KeyPurpose.AGREE_KEY))
                    "devmode_sa_android_mdoc_auth_x448" -> Pair(EcCurve.X448, setOf(KeyPurpose.AGREE_KEY))
                    else -> throw IllegalStateException()
                }
                return CredentialConfiguration(
                    challenge,
                    "AndroidKeystoreSecureArea",
                    Cbor.encode(
                        CborMap.builder()
                            .put("useStrongBox", useStrongBox)
                            .put("userAuthenticationRequired", (userAuthType != 0))
                            .put("userAuthenticationTimeoutMillis", 0L)
                            .put("userAuthenticationTypes", userAuthType)
                            .put("curve", curve.coseCurveIdentifier)
                            .put("purposes", KeyPurpose.encodeSet(purposes))
                            .end().build()
                    )
                )

            }

            "devmode_sa_software" -> {
                val (curve, purposes) = when ((collectedEvidence["devmode_sa_software_mdoc_auth"]
                        as EvidenceResponseQuestionMultipleChoice).answerId) {
                    "devmode_sa_software_mdoc_auth_ecdsa_p256" -> Pair(EcCurve.P256, setOf(KeyPurpose.SIGN))
                    "devmode_sa_software_mdoc_auth_ecdsa_p384" -> Pair(EcCurve.P384, setOf(KeyPurpose.SIGN))
                    "devmode_sa_software_mdoc_auth_ecdsa_p521" -> Pair(EcCurve.P521, setOf(KeyPurpose.SIGN))
                    "devmode_sa_software_mdoc_auth_ecdsa_brainpoolp256r1" -> Pair(EcCurve.BRAINPOOLP256R1, setOf(KeyPurpose.SIGN))
                    "devmode_sa_software_mdoc_auth_ecdsa_brainpoolp320r1" -> Pair(EcCurve.BRAINPOOLP320R1, setOf(KeyPurpose.SIGN))
                    "devmode_sa_software_mdoc_auth_ecdsa_brainpoolp384r1" -> Pair(EcCurve.BRAINPOOLP384R1, setOf(KeyPurpose.SIGN))
                    "devmode_sa_software_mdoc_auth_ecdsa_brainpoolp512r1" -> Pair(EcCurve.BRAINPOOLP512R1, setOf(KeyPurpose.SIGN))
                    "devmode_sa_software_mdoc_auth_ed25519" -> Pair(EcCurve.ED25519, setOf(KeyPurpose.SIGN))
                    "devmode_sa_software_mdoc_auth_ed448" -> Pair(EcCurve.ED448, setOf(KeyPurpose.SIGN))
                    "devmode_sa_software_mdoc_auth_ecdh_p256" -> Pair(EcCurve.P256, setOf(KeyPurpose.AGREE_KEY))
                    "devmode_sa_software_mdoc_auth_ecdh_p384" -> Pair(EcCurve.P384, setOf(KeyPurpose.AGREE_KEY))
                    "devmode_sa_software_mdoc_auth_ecdh_p521" -> Pair(EcCurve.P521, setOf(KeyPurpose.AGREE_KEY))
                    "devmode_sa_software_mdoc_auth_ecdh_brainpoolp256r1" -> Pair(EcCurve.BRAINPOOLP256R1, setOf(KeyPurpose.AGREE_KEY))
                    "devmode_sa_software_mdoc_auth_ecdh_brainpoolp320r1" -> Pair(EcCurve.BRAINPOOLP320R1, setOf(KeyPurpose.AGREE_KEY))
                    "devmode_sa_software_mdoc_auth_ecdh_brainpoolp384r1" -> Pair(EcCurve.BRAINPOOLP384R1, setOf(KeyPurpose.AGREE_KEY))
                    "devmode_sa_software_mdoc_auth_ecdh_brainpoolp512r1" -> Pair(EcCurve.BRAINPOOLP512R1, setOf(KeyPurpose.AGREE_KEY))
                    "devmode_sa_software_mdoc_auth_x25519" -> Pair(EcCurve.X25519, setOf(KeyPurpose.AGREE_KEY))
                    "devmode_sa_software_mdoc_auth_x448" -> Pair(EcCurve.X448, setOf(KeyPurpose.AGREE_KEY))
                    else -> throw IllegalStateException()
                }
                var passphrase: String? = null
                val passphraseConstraints = when ((collectedEvidence["devmode_sa_software_passphrase_complexity"]
                        as EvidenceResponseQuestionMultipleChoice).answerId) {
                    "devmode_sa_software_passphrase_none" -> null
                    "devmode_sa_software_passphrase_6_digit_pin" -> {
                        passphrase = (collectedEvidence["devmode_sa_software_passphrase"]
                                as EvidenceResponseCreatePassphrase).passphrase
                        PassphraseConstraints.PIN_SIX_DIGITS
                    }
                    "devmode_sa_software_passphrase_8_char_or_longer_passphrase" -> {
                        passphrase = (collectedEvidence["devmode_sa_software_passphrase"]
                                as EvidenceResponseCreatePassphrase).passphrase
                        PassphraseConstraints(8, Int.MAX_VALUE, false)
                    }
                    else -> throw IllegalStateException()
                }
                val builder = CborMap.builder()
                    .put("curve", curve.coseCurveIdentifier)
                    .put("purposes", KeyPurpose.encodeSet(purposes))
                if (passphrase != null) {
                    builder.put("passphrase", passphrase)
                }
                if (passphraseConstraints != null) {
                    builder.put("passphraseConstraints", passphraseConstraints.toDataItem)
                }
                return CredentialConfiguration(
                    challenge,
                    "SoftwareSecureArea",
                    Cbor.encode(builder.end().build())
                )
            }
            else -> {
                throw IllegalStateException("Unexpected value $chosenSa")
            }
        }
    }

    private fun createDocumentConfiguration(collectedEvidence: Map<String, EvidenceResponse>?): DocumentConfiguration {
        val baos = ByteArrayOutputStream()
        BitmapFactory.decodeResource(
            application.applicationContext.resources, R.drawable.utopia_driving_license_card_art
        ).compress(Bitmap.CompressFormat.PNG, 90, baos)
        val cardArt: ByteArray = baos.toByteArray()

        if (collectedEvidence == null) {
            return DocumentConfiguration(
                resourceString(R.string.utopia_mdl_issuing_authority_pending_document_title),
                cardArt,
                MDL_DOCTYPE,
                NameSpacedData.Builder().build(),
                true
            )
        }

        val staticData: NameSpacedData

        val now = Clock.System.now()
        val issueDate = now
        val expiryDate = now + 365.days * 5

        val credType = application.documentTypeRepository.getDocumentTypeForMdoc(MDL_DOCTYPE)!!

        val path = (collectedEvidence["path"] as EvidenceResponseQuestionMultipleChoice).answerId
        if (path == "hardcoded") {
            staticData = getSampleData(credType).build()
        } else {
            val icaoPassiveData = collectedEvidence["passive"]
            val icaoTunnelData = collectedEvidence["tunnel"]
            val mrtdData = if (icaoTunnelData is EvidenceResponseIcaoNfcTunnelResult)
                MrtdNfcData(icaoTunnelData.dataGroups, icaoTunnelData.securityObject)
            else if (icaoPassiveData is EvidenceResponseIcaoPassiveAuthentication)
                MrtdNfcData(icaoPassiveData.dataGroups, icaoPassiveData.securityObject)
            else
                throw IllegalStateException("Should not happen")
            val decoder = MrtdNfcDataDecoder(application.cacheDir)
            val decoded = decoder.decode(mrtdData)
            val firstName = decoded.firstName
            val lastName = decoded.lastName
            val sex = when (decoded.gender) {
                "MALE" -> 1L
                "FEMALE" -> 2L
                else -> 0L
            }
            val timeZone = TimeZone.currentSystemDefault()
            val dateOfBirth = LocalDate.parse(input = decoded.dateOfBirth,
                format = LocalDate.Format {
                    // date of birth cannot be in future
                    yearTwoDigits(now.toLocalDateTime(timeZone).year - 99)
                    monthNumber()
                    dayOfMonth()
                })
            val dateOfBirthInstant = dateOfBirth.atStartOfDayIn(timeZone)
            // over 18/21 is calculated purely based on calendar date (not based on the birth time zone)
            val ageOver18 = now > dateOfBirthInstant.plus(18, DateTimeUnit.YEAR, timeZone)
            val ageOver21 = now > dateOfBirthInstant.plus(21, DateTimeUnit.YEAR, timeZone)
            val portrait = decoded.photo ?: bitmapData(R.drawable.img_erika_portrait)
            val signatureOrUsualMark = decoded.signature ?: bitmapData(R.drawable.img_erika_signature)

            // Make sure we set at least all the mandatory data elements
            //
            staticData = NameSpacedData.Builder()
                .putEntryString(MDL_NAMESPACE, "given_name", firstName)
                .putEntryString(MDL_NAMESPACE, "family_name", lastName)
                .putEntry(MDL_NAMESPACE, "birth_date",
                        Cbor.encode(dateOfBirth.toDataItemFullDate))
                .putEntryByteString(MDL_NAMESPACE, "portrait", portrait)
                .putEntryByteString(MDL_NAMESPACE, "signature_usual_mark", signatureOrUsualMark)
                .putEntryNumber(MDL_NAMESPACE, "sex", sex)
                .putEntry(MDL_NAMESPACE, "issue_date",
                    Cbor.encode(issueDate.toDataItemDateTimeString))
                .putEntry(MDL_NAMESPACE, "expiry_date",
                    Cbor.encode(expiryDate.toDataItemDateTimeString)
                )
                .putEntryString(MDL_NAMESPACE, "issuing_authority",
                    resourceString(R.string.utopia_mdl_issuing_authority_name),)
                .putEntryString(MDL_NAMESPACE, "issuing_country", "UT")
                .putEntryString(MDL_NAMESPACE, "un_distinguishing_sign", "UTO")
                .putEntryString(MDL_NAMESPACE, "document_number", "1234567890")
                .putEntryString(MDL_NAMESPACE, "administrative_number", "123456789")
                .putEntry(MDL_NAMESPACE, "driving_privileges",
                    Cbor.encode(CborArray.builder().end().build()))

                .putEntryBoolean(MDL_NAMESPACE, "age_over_18", ageOver18)
                .putEntryBoolean(MDL_NAMESPACE, "age_over_21", ageOver21)

                .putEntryString(AAMVA_NAMESPACE, "DHS_compliance", "F")
                .putEntryNumber(AAMVA_NAMESPACE, "EDL_credential", 1)
                .putEntryNumber(AAMVA_NAMESPACE, "sex", sex)
                .build()
        }

        val firstName = staticData.getDataElementString(MDL_NAMESPACE, "given_name")
        return DocumentConfiguration(
            resourceString(R.string.utopia_mdl_issuing_authority_document_title, firstName),
            cardArt,
            MDL_DOCTYPE,
            staticData,
            true
        )
    }

    override fun developerModeRequestUpdate(currentConfiguration: DocumentConfiguration): DocumentConfiguration {
        // The update consists of just slapping an extra 0 at the end of `administrative_number`
        val newAdministrativeNumber = try {
            currentConfiguration.staticData
                .getDataElementString(MDL_NAMESPACE, "administrative_number")
        } catch (e: Throwable) {
            ""
        } + "0"


        val builder = NameSpacedData.Builder(currentConfiguration.staticData)
        builder.putEntryString(
            MDL_NAMESPACE,
            "administrative_number",
            newAdministrativeNumber
        )

        return DocumentConfiguration(
            displayName = currentConfiguration.displayName,
            cardArt = currentConfiguration.cardArt,
            mdocDocType = currentConfiguration.mdocDocType,
            staticData = builder.build(),
            requireUserAuthenticationToViewDocument = false
        )
    }

    private fun getSampleData(documentType: DocumentType): NameSpacedData.Builder {
        val portrait = bitmapData(R.drawable.img_erika_portrait)
        val signatureOrUsualMark = bitmapData(R.drawable.img_erika_signature)
        val builder = NameSpacedData.Builder()
        for ((namespaceName, namespace) in documentType.mdocDocumentType!!.namespaces) {
            for ((dataElementName, dataElement) in namespace.dataElements) {
                if (dataElement.attribute.sampleValue != null) {
                    builder.putEntry(
                        namespaceName,
                        dataElementName,
                        Cbor.encode(dataElement.attribute.sampleValue!!)
                    )
                }
            }
        }
        // Sample data currently doesn't have portrait or signature_usual_mark
        builder
            .putEntryByteString(MDL_NAMESPACE, "portrait", portrait)
            .putEntryByteString(MDL_NAMESPACE, "signature_usual_mark", signatureOrUsualMark)
        return builder
    }
}