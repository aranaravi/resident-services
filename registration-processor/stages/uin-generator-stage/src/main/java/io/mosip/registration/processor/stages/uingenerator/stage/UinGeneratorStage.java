package io.mosip.registration.processor.stages.uingenerator.stage;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import io.mosip.kernel.core.fsadapter.exception.FSAdapterException;
import io.mosip.kernel.core.fsadapter.spi.FileSystemAdapter;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.CryptoUtil;
import io.mosip.kernel.core.util.DateUtils;
import io.mosip.registration.processor.core.abstractverticle.MessageBusAddress;
import io.mosip.registration.processor.core.abstractverticle.MessageDTO;
import io.mosip.registration.processor.core.abstractverticle.MosipEventBus;
import io.mosip.registration.processor.core.abstractverticle.MosipVerticleManager;
import io.mosip.registration.processor.core.code.ApiName;
import io.mosip.registration.processor.core.constant.EventId;
import io.mosip.registration.processor.core.constant.EventName;
import io.mosip.registration.processor.core.constant.EventType;
import io.mosip.registration.processor.core.constant.JsonConstant;
import io.mosip.registration.processor.core.constant.LoggerFileConstant;
import io.mosip.registration.processor.core.constant.PacketFiles;
import io.mosip.registration.processor.core.exception.ApisResourceAccessException;
import io.mosip.registration.processor.core.exception.util.PacketStructure;
import io.mosip.registration.processor.core.exception.util.PlatformErrorMessages;
import io.mosip.registration.processor.core.logger.RegProcessorLogger;
import io.mosip.registration.processor.core.packet.dto.ApplicantDocument;
import io.mosip.registration.processor.core.packet.dto.FieldValueArray;
import io.mosip.registration.processor.core.packet.dto.Identity;
import io.mosip.registration.processor.core.packet.dto.PacketMetaInfo;
import io.mosip.registration.processor.core.packet.dto.demographicinfo.identify.RegistrationProcessorIdentity;
import io.mosip.registration.processor.core.spi.packetmanager.PacketInfoManager;
import io.mosip.registration.processor.core.spi.restclient.RegistrationProcessorRestClientService;
import io.mosip.registration.processor.core.util.IdentityIteratorUtil;
import io.mosip.registration.processor.core.util.JsonUtil;
import io.mosip.registration.processor.packet.storage.dto.ApplicantInfoDto;
import io.mosip.registration.processor.packet.storage.entity.IndividualDemographicDedupeEntity;
import io.mosip.registration.processor.packet.storage.repository.BasePacketRepository;
import io.mosip.registration.processor.packet.storage.utils.Utilities;
import io.mosip.registration.processor.rest.client.audit.builder.AuditLogRequestBuilder;
import io.mosip.registration.processor.stages.uingenerator.dto.UinResponseDto;
import io.mosip.registration.processor.stages.uingenerator.idrepo.dto.Documents;
import io.mosip.registration.processor.stages.uingenerator.idrepo.dto.IdRequestDto;
import io.mosip.registration.processor.stages.uingenerator.idrepo.dto.IdResponseDTO;
import io.mosip.registration.processor.stages.uingenerator.idrepo.dto.RequestDto;
import io.mosip.registration.processor.stages.uingenerator.util.UinStatusMessage;
import io.mosip.registration.processor.status.code.RegistrationStatusCode;
import io.mosip.registration.processor.status.dto.InternalRegistrationStatusDto;
import io.mosip.registration.processor.status.dto.RegistrationStatusDto;
import io.mosip.registration.processor.status.service.RegistrationStatusService;

/**
 * The Class UinGeneratorStage.
 * 
 * @author Ranjitha Siddegowda
 * @author Rishab Kesari
 */
@Service
public class UinGeneratorStage extends MosipVerticleManager {

	/** The reg proc logger. */
	private static Logger regProcLogger = RegProcessorLogger.getLogger(UinGeneratorStage.class);

	/** The Constant USER. */
	private static final String USER = "MOSIP_SYSTEM";

	/** The mosip event bus. */
	MosipEventBus mosipEventBus = null;

	/** The cluster address. */
	@Value("${registration.processor.vertx.cluster.address}")
	private String clusterAddress;

	/** The localhost. */
	@Value("${registration.processor.vertx.localhost}")
	private String localhost;

	/** The cluster manager url. */
	@Value("${vertx.ignite.configuration}")
	private String clusterManagerUrl;

	/** The id repo create. */
	@Value("${registration.processor.id.repo.create}")
	private String idRepoCreate;

	/** The id repo update. */
	@Value("${registration.processor.id.repo.update}")
	private String idRepoUpdate;

	/** The adapter. */
	@Autowired
	private FileSystemAdapter adapter;

	/** The core audit request builder. */
	@Autowired
	private AuditLogRequestBuilder auditLogRequestBuilder;

	/** The registration processor rest client service. */
	@Autowired
	RegistrationProcessorRestClientService<Object> registrationProcessorRestClientService;

	/** The demographic dedupe repository. */
	@Autowired
	private BasePacketRepository<IndividualDemographicDedupeEntity, String> demographicDedupeRepository;

	/** The packet info manager. */
	@Autowired
	private PacketInfoManager<Identity, ApplicantInfoDto> packetInfoManager;

	/** The registration id. */
	private String registrationId = "";

	/** The id response DTO. */
	/** The Constant FILE_SEPARATOR. */
	public static final String FILE_SEPARATOR = "\\";

	/** The id response DTO. */
	IdResponseDTO idResponseDTO = new IdResponseDTO();

	/** The id request DTO. */
	IdRequestDto idRequestDTO = new IdRequestDto();

	/** The identity json. */
	JSONObject identityJson = null;

	/** The demographic identity. */
	JSONObject demographicIdentity = null;

	/** The registration status service. */
	@Autowired
	RegistrationStatusService<String, InternalRegistrationStatusDto, RegistrationStatusDto> registrationStatusService;

	/** The id repo api version. */
	private String idRepoApiVersion = "1.0";

	/** The reg processor identity json. */
	@Autowired
	private RegistrationProcessorIdentity regProcessorIdentityJson;

	/** The utility. */
	@Autowired
	private Utilities utility;

	/** The identity iterator util. */
	IdentityIteratorUtil identityIteratorUtil = new IdentityIteratorUtil();
	private static final String UIN_FAILURE = "UIN updation failure for registrationId ";
	private static final String NULL_IDREPO_RESPONSE = "Response from IdRepo is null";
	private String description = "";
	private boolean isTransactionSuccessful = false;
	private static final String UIN_GENERATION_FAILED = "UIN Generation failed :";

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * io.mosip.registration.processor.core.spi.eventbus.EventBusManager#process(
	 * java.lang.Object)
	 */
	@SuppressWarnings("unchecked")
	@Override
	public MessageDTO process(MessageDTO object) {

		object.setMessageBusAddress(MessageBusAddress.UIN_GENERATION_BUS_IN);
		object.setInternalError(Boolean.FALSE);
		object.setIsValid(true);
		this.registrationId = object.getRid();
		regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
				registrationId, "UinGeneratorStage::process()::entry");
		UinResponseDto uinResponseDto = null;
		InternalRegistrationStatusDto registrationStatusDto = registrationStatusService
				.getRegistrationStatus(registrationId);
		try {

			InputStream idJsonStream = adapter.getFile(registrationId,
					PacketFiles.DEMOGRAPHIC.name() + FILE_SEPARATOR + PacketFiles.ID.name());
			byte[] idJsonBytes = IOUtils.toByteArray(idJsonStream);
			String getJsonStringFromBytes = new String(idJsonBytes);
			JSONParser parser = new JSONParser();
			identityJson = (JSONObject) parser.parse(getJsonStringFromBytes);
			demographicIdentity = (JSONObject) identityJson.get("identity");
			String uinFieldCheck = (String) demographicIdentity.get("UIN");
			boolean isUinCreate = false;
			if (uinFieldCheck == null || uinFieldCheck.isEmpty()) {
				String test = (String) registrationProcessorRestClientService.getApi(ApiName.UINGENERATOR, null, "", "",
						String.class);
				Gson gsonObj = new Gson();
				uinResponseDto = gsonObj.fromJson(test, UinResponseDto.class);
				long uinInLong = Long.parseLong(uinResponseDto.getUin());
				demographicIdentity.put("UIN", uinInLong);
				idResponseDTO = sendIdRepoWithUin(registrationId, uinResponseDto.getUin());

				Gson gson = new GsonBuilder().create();
				String idResponse = gson.toJson(idResponseDTO);
				regProcLogger.info(LoggerFileConstant.SESSIONID.toString(),
						LoggerFileConstant.REGISTRATIONID.toString() + registrationId, "Response from IdRepo API",
						"is :" + idResponse);
				isUinCreate = true;
			} else {
				idResponseDTO = updateIdRepowithUin(registrationId, uinFieldCheck);
			}
			if (idResponseDTO != null && idResponseDTO.getResponse() != null) {
				if (isUinCreate) {
					demographicDedupeRepository.updateUinWrtRegistraionId(registrationId, uinResponseDto.getUin());
					// triggerNotification.triggerNotification(uinResponseDto.getUin(),
					// NotificationTemplateType.UIN_CREATED);
				} else {
					// triggerNotification.triggerNotification(uinFieldCheck,
					// NotificationTemplateType.UIN_UPDATE);

				}

				registrationStatusDto.setStatusComment(UinStatusMessage.PACKET_UIN_UPDATION_SUCCESS_MSG);
				registrationStatusDto.setStatusCode(RegistrationStatusCode.PACKET_UIN_UPDATION_SUCCESS.toString());
				isTransactionSuccessful = true;
				description = "UIN updated succesfully for registrationId " + registrationId;
				regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(),
						LoggerFileConstant.REGISTRATIONID.toString(), registrationId,
						"UinGeneratorStage::process()::exit");
				regProcLogger.info(LoggerFileConstant.SESSIONID.toString(),
						LoggerFileConstant.REGISTRATIONID.toString(), registrationId, description);
			} else {
				String statusComment = idResponseDTO != null && idResponseDTO.getErrors() != null
						? idResponseDTO.getErrors().get(0).getErrorMessage()
						: NULL_IDREPO_RESPONSE;
				registrationStatusDto.setStatusComment(statusComment);
				object.setInternalError(Boolean.TRUE);
				registrationStatusDto.setStatusCode(RegistrationStatusCode.PACKET_UIN_UPDATION_FAILURE.toString());
				isTransactionSuccessful = false;
				description = UIN_FAILURE + registrationId + "::" + idResponseDTO != null
						&& idResponseDTO.getErrors() != null ? idResponseDTO.getErrors().get(0).getErrorMessage()
								: NULL_IDREPO_RESPONSE;
				regProcLogger.error(LoggerFileConstant.SESSIONID.toString(),
						LoggerFileConstant.REGISTRATIONID.toString(), registrationId,
						idResponseDTO != null ? idResponseDTO.getErrors().get(0).getErrorMessage()
								: NULL_IDREPO_RESPONSE + "  :  " + idResponseDTO != null ? idResponseDTO.toString()
										: NULL_IDREPO_RESPONSE);

			}

			registrationStatusDto.setUpdatedBy(USER);
			registrationStatusService.updateRegistrationStatus(registrationStatusDto);
		} catch (FSAdapterException e) {
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationId,
					PlatformErrorMessages.RPR_UGS_PACKET_STORE_NOT_ACCESSIBLE.getMessage() + e.getMessage());
			object.setInternalError(Boolean.TRUE);
			isTransactionSuccessful = false;
			description = "FileSytem is not accessible for packet " + registrationId + "::" + e.getMessage();
			object.setIsValid(Boolean.FALSE);
			object.setRid(registrationId);
		} catch (ApisResourceAccessException ex) {
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationId, RegistrationStatusCode.PACKET_UIN_UPDATION_SUCCESS.toString() + ex.getMessage()
							+ ExceptionUtils.getStackTrace(ex));
			object.setInternalError(Boolean.TRUE);
			description = "Internal error occured in UINGenerator stage while processing registrationId "
					+ registrationId + "::" + ex.getMessage();
		} catch (Exception ex) {
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationId, RegistrationStatusCode.PACKET_UIN_UPDATION_SUCCESS.toString() + ex.getMessage()
							+ ExceptionUtils.getStackTrace(ex));
			object.setInternalError(Boolean.TRUE);
			description = "Internal error occured in UINGenerator stage while processing registrationId "
					+ registrationId + ex.getMessage();
		} finally {

			String eventId = isTransactionSuccessful ? EventId.RPR_402.toString() : EventId.RPR_405.toString();
			String eventName = eventId.equalsIgnoreCase(EventId.RPR_402.toString()) ? EventName.UPDATE.toString()
					: EventName.EXCEPTION.toString();
			String eventType = eventId.equalsIgnoreCase(EventId.RPR_402.toString()) ? EventType.BUSINESS.toString()
					: EventType.SYSTEM.toString();
			auditLogRequestBuilder.createAuditRequestBuilder(description, eventId, eventName, eventType,
					registrationId);

		}

		return object;
	}

	/**
	 * Send id repo with uin.
	 *
	 * @param regId
	 *            the reg id
	 * @param uin
	 *            the uin
	 * @return the id response DTO
	 * @throws ApisResourceAccessException
	 * @throws Exception
	 */
	private IdResponseDTO sendIdRepoWithUin(String regId, String uin) throws ApisResourceAccessException {

		List<Documents> documentInfo = getAllDocumentsByRegId(regId);
		RequestDto requestDto = new RequestDto();
		requestDto.setIdentity(demographicIdentity);
		requestDto.setDocuments(documentInfo);

		List<String> pathsegments = new ArrayList<>();
		pathsegments.add(uin);
		IdResponseDTO result = null;
		idRequestDTO.setId(idRepoCreate);
		idRequestDTO.setRegistrationId(regId);
		idRequestDTO.setRequest(requestDto);
		idRequestDTO.setTimestamp(DateUtils.getUTCCurrentDateTimeString());
		idRequestDTO.setVersion(idRepoApiVersion);
		Gson gson = new GsonBuilder().create();
		String idRequest = gson.toJson(idRequestDTO);
		regProcLogger.info(LoggerFileConstant.SESSIONID.toString(),
				LoggerFileConstant.REGISTRATIONID.toString() + regId, "Request to IdRepo API", "is: " + idRequest);
		try {
			result = (IdResponseDTO) registrationProcessorRestClientService.postApi(ApiName.IDREPOSITORY, pathsegments,
					"", "", idRequestDTO, IdResponseDTO.class);
			regProcLogger.info(LoggerFileConstant.SESSIONID.toString(),
					LoggerFileConstant.REGISTRATIONID.toString() + regId, "Response from IdRepo API",
					"is : " + result.toString());

		} catch (ApisResourceAccessException e) {
			// regProcLogger.error(LoggerFileConstant.SESSIONID.toString(),
			// LoggerFileConstant.REGISTRATIONID.toString(),
			// registrationId,
			// PlatformErrorMessages.RPR_SYS_JSON_PARSING_EXCEPTION.getMessage() +
			// e.getMessage()
			// + ExceptionUtils.getStackTrace(e));
			//
			// regProcLogger.error(LoggerFileConstant.SESSIONID.toString(),
			// LoggerFileConstant.REGISTRATIONID.toString(),
			// registrationId, "Internal Error Occured ");
			if (e.getCause() instanceof HttpClientErrorException) {
				HttpClientErrorException httpClientException = (HttpClientErrorException) e.getCause();
				description = UIN_GENERATION_FAILED + registrationId + "::"
						+ httpClientException.getResponseBodyAsString();
				throw new ApisResourceAccessException(httpClientException.getResponseBodyAsString(),
						httpClientException);
			} else if (e.getCause() instanceof HttpServerErrorException) {
				HttpServerErrorException httpServerException = (HttpServerErrorException) e.getCause();
				description = UIN_GENERATION_FAILED + registrationId + "::"
						+ httpServerException.getResponseBodyAsString();

				throw new ApisResourceAccessException(httpServerException.getResponseBodyAsString(),
						httpServerException);
			} else {
				description = UIN_GENERATION_FAILED + registrationId + "::" + e.getMessage();
				throw new ApisResourceAccessException(UIN_GENERATION_FAILED, e);
			}

		}
		return result;

	}

	/**
	 * Gets the all documents by reg id.
	 *
	 * @param regId
	 *            the reg id
	 * @return the all documents by reg id
	 */
	private List<Documents> getAllDocumentsByRegId(String regId) {
		List<Documents> applicantDocuments = new ArrayList<>();
		Documents documentsInfoDto = null;
		List<ApplicantDocument> applicantDocument = packetInfoManager.getDocumentsByRegId(regId);
		// mocked adding biometric files to idrepo
		// applicantDocuments.add(addBiometricDetails(regId));
		for (ApplicantDocument entity : applicantDocument) {
			documentsInfoDto = new Documents();
			documentsInfoDto.setCategory(entity.getDocName());
			documentsInfoDto.setValue(CryptoUtil.encodeBase64(entity.getDocStore()));
			applicantDocuments.add(documentsInfoDto);
		}
		return applicantDocuments;
	}

	/**
	 * Adds the biometric details.
	 *
	 * @param regId
	 *            the reg id
	 * @return the documents
	 */
	private Documents addBiometricDetails(String regId) {
		Documents document = new Documents();

		byte[] biometricDocument = getFile(regId);
		String getIdentityJsonString = Utilities.getJson(utility.getConfigServerFileStorageURL(),
				utility.getGetRegProcessorIdentityJson());
		ObjectMapper mapIdentityJsonStringToObject = new ObjectMapper();
		try {
			regProcessorIdentityJson = mapIdentityJsonStringToObject.readValue(getIdentityJsonString,
					RegistrationProcessorIdentity.class);
		} catch (IOException e) {
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					regId, PlatformErrorMessages.RPR_SYS_IO_EXCEPTION.getMessage() + e.getMessage());
		}
		String individuaBiometricValue = regProcessorIdentityJson.getIdentity().getIndividualBiometrics().getValue();
		document.setCategory(individuaBiometricValue);
		document.setValue(CryptoUtil.encodeBase64(biometricDocument));
		return document;
	}

	/**
	 * Gets the file.
	 *
	 * @param registrationId
	 *            the registration id
	 * @return the file
	 */
	private byte[] getFile(String registrationId) {
		byte[] file = null;
		InputStream packetMetaInfoStream = adapter.getFile(registrationId, PacketFiles.PACKET_META_INFO.name());
		PacketMetaInfo packetMetaInfo = null;
		String applicantBiometricFileName = "";
		try {
			packetMetaInfo = (PacketMetaInfo) JsonUtil.inputStreamtoJavaObject(packetMetaInfoStream,
					PacketMetaInfo.class);

			List<FieldValueArray> hashSequence = packetMetaInfo.getIdentity().getHashSequence1();
			List<String> hashList = identityIteratorUtil.getHashSequence(hashSequence,
					JsonConstant.APPLICANTBIOMETRICSEQUENCE);
			if (hashList != null)
				applicantBiometricFileName = hashList.get(0);
			InputStream fileInStream = adapter.getFile(registrationId,
					PacketStructure.BIOMETRIC + applicantBiometricFileName.toUpperCase());
			file = IOUtils.toByteArray(fileInStream);

		} catch (IOException e) {
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationId, PlatformErrorMessages.RPR_SYS_IO_EXCEPTION.getMessage() + e.getMessage());
		}
		return file;
	}

	/**
	 * Update id repowith uin.
	 *
	 * @param regId
	 *            the reg id
	 * @param uin
	 *            the uin
	 * @return the id response DTO
	 */
	private IdResponseDTO updateIdRepowithUin(String regId, String uin) {
		List<Documents> documentInfo = getAllDocumentsByRegId(regId);
		RequestDto requestDto = new RequestDto();
		requestDto.setIdentity(demographicIdentity);
		requestDto.setDocuments(documentInfo);

		List<String> pathsegments = new ArrayList<>();
		pathsegments.add(uin);

		idRequestDTO.setId(idRepoUpdate);
		idRequestDTO.setRegistrationId(regId);
		idRequestDTO.setRequest(requestDto);
		idRequestDTO.setTimestamp(DateUtils.formatToISOString(LocalDateTime.now()));
		idRequestDTO.setVersion(idRepoApiVersion);
		Gson gson = new GsonBuilder().create();
		String idReq = gson.toJson(idResponseDTO);
		IdResponseDTO result = null;

		regProcLogger.info(LoggerFileConstant.SESSIONID.toString(),
				LoggerFileConstant.REGISTRATIONID.toString() + regId, "Update Request to IdRepo API", "is : " + idReq);

		try {
			result = (IdResponseDTO) registrationProcessorRestClientService.postApi(ApiName.IDREPOSITORY, pathsegments,
					"", "", idRequestDTO, IdResponseDTO.class);
			// Gson gsonObj = new Gson();
			// idResponseDTO = gsonObj.fromJson(result, IdResponseDTO.class);
			regProcLogger.info(LoggerFileConstant.SESSIONID.toString(),
					LoggerFileConstant.REGISTRATIONID.toString() + regId, "Updated Response from IdRepo API",
					"is : " + result.toString());

		} catch (ApisResourceAccessException e) {
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationId, PlatformErrorMessages.RPR_SYS_JSON_PARSING_EXCEPTION.getMessage() + e.getMessage()
							+ ExceptionUtils.getStackTrace(e));
		} 
		return result;
	}
	
	

	private IdResponseDTO reActivateUin(String regId,String uin) throws ApisResourceAccessException {

		IdResponseDTO result = getIdRepoDataByUIN(uin);

		List<String> pathsegments = new ArrayList<>();
		try {
			if (result != null && result.getResponse() != null) {
				
				if(result.getStatus().equalsIgnoreCase("ACTIVATED")) {

					return result;
					
				}else {

					RequestDto requestDto = new RequestDto();
					requestDto.setIdentity(result.getResponse().getIdentity());
					requestDto.setDocuments(result.getResponse().getDocuments());

					pathsegments.add(uin);

					idRequestDTO.setId(idRepoUpdate);
					idRequestDTO.setRegistrationId(regId);
					idRequestDTO.setStatus("ACTIVATED");
					idRequestDTO.setRequest(requestDto);
					idRequestDTO.setTimestamp(DateUtils.formatToISOString(LocalDateTime.now()));
					idRequestDTO.setVersion(idRepoApiVersion);
					Gson gson = new GsonBuilder().create();
					String idReq = gson.toJson(idResponseDTO);

					regProcLogger.info(LoggerFileConstant.SESSIONID.toString(),
							LoggerFileConstant.REGISTRATIONID.toString() + regId, "Update Request to IdRepo API", "is : " + idReq);


					result = (IdResponseDTO) registrationProcessorRestClientService.postApi(ApiName.IDREPOSITORY, pathsegments,
							"", "", idRequestDTO, IdResponseDTO.class);
					regProcLogger.info(LoggerFileConstant.SESSIONID.toString(),
							LoggerFileConstant.REGISTRATIONID.toString() + regId, "Updated Response from IdRepo API",
							"is : " + result.toString());


				}

			}
		} catch (ApisResourceAccessException e) {
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationId, PlatformErrorMessages.RPR_SYS_JSON_PARSING_EXCEPTION.getMessage() + e.getMessage()
					+ ExceptionUtils.getStackTrace(e));
		}
		return result;
	}

	
	
	
	private IdResponseDTO getIdRepoDataByUIN(String uin) throws ApisResourceAccessException{
		IdResponseDTO response  = new IdResponseDTO();
		
		List<String> pathsegments = new ArrayList<>();
		pathsegments.add(uin);
		try {
			response = (IdResponseDTO) registrationProcessorRestClientService.getApi(ApiName.IDREPOSITORY, pathsegments, "",
					"", IdResponseDTO.class);
		} catch (ApisResourceAccessException e) {
			if (e.getCause() instanceof HttpClientErrorException) {
				HttpClientErrorException httpClientException = (HttpClientErrorException) e.getCause();
				description = UIN_GENERATION_FAILED + registrationId + "::"
						+ httpClientException.getResponseBodyAsString();
				throw new ApisResourceAccessException(httpClientException.getResponseBodyAsString(),
						httpClientException);
			} else if (e.getCause() instanceof HttpServerErrorException) {
				HttpServerErrorException httpServerException = (HttpServerErrorException) e.getCause();
				description = UIN_GENERATION_FAILED + registrationId + "::"
						+ httpServerException.getResponseBodyAsString();

				throw new ApisResourceAccessException(httpServerException.getResponseBodyAsString(),
						httpServerException);
			} else {
				description = UIN_GENERATION_FAILED + registrationId + "::" + e.getMessage();
				throw new ApisResourceAccessException(UIN_GENERATION_FAILED, e);
			}
		}
		return response;
	}

	/**
	 * Deploy verticle.
	 */
	public void deployVerticle() {
		//
		mosipEventBus = this.getEventBus(this.getClass(), clusterManagerUrl);
		this.consumeAndSend(mosipEventBus, MessageBusAddress.UIN_GENERATION_BUS_IN,
				MessageBusAddress.UIN_GENERATION_BUS_OUT);

	}
}
