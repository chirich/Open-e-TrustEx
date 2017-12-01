package eu.europa.ec.cipa.etrustex.services;

import eu.europa.ec.cipa.etrustex.domain.*;
import eu.europa.ec.cipa.etrustex.domain.admin.BusinessDomain;
import eu.europa.ec.cipa.etrustex.domain.sla.SlaPolicy;
import eu.europa.ec.cipa.etrustex.domain.util.EntityAccessInfo;
import eu.europa.ec.cipa.etrustex.domain.util.MessagesListVO;
import eu.europa.ec.cipa.etrustex.domain.util.MetaDataItem;
import eu.europa.ec.cipa.etrustex.services.dao.IMessageBinaryDAO;
import eu.europa.ec.cipa.etrustex.services.dao.IMessageDAO;
import eu.europa.ec.cipa.etrustex.services.dao.ISlaPolicyDAO;
import eu.europa.ec.cipa.etrustex.services.dao.MessageBinaryDAO;
import eu.europa.ec.cipa.etrustex.services.dto.CreateMessageBinaryDTO;
import eu.europa.ec.cipa.etrustex.services.dto.CreateMessageDTO;
import eu.europa.ec.cipa.etrustex.services.dto.EncryptBinaryDTO;
import eu.europa.ec.cipa.etrustex.services.dto.LogDTO;
import eu.europa.ec.cipa.etrustex.services.dto.SlaPolicySearchDTO;
import eu.europa.ec.cipa.etrustex.services.exception.*;
import eu.europa.ec.cipa.etrustex.services.util.DispatchEnum;
import eu.europa.ec.cipa.etrustex.types.*;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.BooleanUtils;
import org.hibernate.Hibernate;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.persistence.PersistenceException;
import java.io.*;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.*;

public class MessageService implements IMessageService {
	private final static String BASE_FILE_NAME = "etrustex";
	private final static String BASE_FILE_EXT = "bin";
	private IMessageDAO messageDAO;
	private IMessageBinaryDAO messageBinaryDAO;
	private Boolean defaultUseFileStore;
	private String fileStorePath;
	private IMetadataService metadataService;

	@Autowired
	private ILogService logService;

	@Autowired
	private IAuthorisationService authorisationService;

	@Autowired
	private IPartyService partyService;

	@Autowired
	private ITransactionService transactionService;

    @Autowired
    private ISlaPolicyDAO slaPolicyDAO;

	private Boolean localUsefilestore;
	private Boolean usefilestoreMetadata;
	private String localFileStorePath;

	private List<String> listDisks = new ArrayList<String>();
	private Map<String, Long> diskSpace = new HashMap<String, Long>();
	private long total = 0l;

	private static final String WRAPPER_TRANSACTION_NS = "ec:services:wsdl:DocumentWrapper";
	private static final String STORE_WRAPPER_TRANSACTION_REQ_LOCAL_NAME = "StoreDocumentWrapperRequest";

	@PostConstruct
	public void afterInit() {

		logger.info("localUsefilestore=" + localUsefilestore);
		logger.info("usefilestoreMetadata=" + usefilestoreMetadata);
		logger.info("localFileStorePath=" + localFileStorePath);

	}

	@Override
	synchronized public void reloadFileStoreParameters(boolean forceReload) throws MessageCreationException {
		Map<MetaDataItemType, MetaDataItem> metadata = metadataService.retrieveMetaData((Long) null, null, null, null);
		defaultUseFileStore = true;
		String pListPath = "";

		if (usefilestoreMetadata) {
			logger.info("use file store meta data");
			if (metadata.containsKey(MetaDataItemType.DEFAULT_USE_FILE_STORE) && metadata.containsKey(MetaDataItemType.FILE_STORE_PATH)) {
				String defaultUseFileStoreStr = metadata.get(MetaDataItemType.DEFAULT_USE_FILE_STORE).getValue();
				defaultUseFileStore = Boolean.parseBoolean(defaultUseFileStoreStr);
				pListPath = metadata.get(MetaDataItemType.FILE_STORE_PATH).getValue();
			} else {
				throw new MessageCreationException(ErrorResponseCode.TECHNICAL_ERROR.getDescription(), ErrorResponseCode.TECHNICAL_ERROR);
			}

		} else {
			logger.info("don't use file store meta data");
			defaultUseFileStore = localUsefilestore;
			logger.info("localUsefilestore=" + localUsefilestore);
			pListPath = localFileStorePath;
			logger.info("localFileStorePath=" + localFileStorePath);
		}

		if (pListPath == null) {
			throw new MessageCreationException(ErrorResponseCode.TECHNICAL_ERROR.getDescription(), ErrorResponseCode.TECHNICAL_ERROR);
		}

		boolean reloaded = false;
		if (!pListPath.equals(this.fileStorePath)) {// only configure if
													// relevant (first time or
													// config change occured)
			this.fileStorePath = pListPath;
			String[] splitList = pListPath.split(",");
			List<String> paths = Arrays.asList(splitList);
			listDisks = paths;
			reloaded = true;
		}
		if (reloaded || forceReload) {// init stats
			diskSpace.clear();
			for (String k : listDisks) {
				File f = new File(k);
				if (f.isDirectory()) {
					long free = f.getFreeSpace();
					diskSpace.put(k, free);
				} else {
					diskSpace.put(k, 0l);
				}
			}
			total = 0l;
			for (String key : listDisks) {
				total = total + diskSpace.get(key);
			}
		}
	}

	synchronized private void updateStats(String path, long consumedBytes) {
		diskSpace.put(path, diskSpace.get(path) - consumedBytes);
		total = total - consumedBytes;
	}

	synchronized private String selectStore() throws MessageCreationException {
		long value = (long) (Math.random() * total);
		long total = 0;
		for (String k : listDisks) {
			total += diskSpace.get(k);
			if (value < total)
				return k;
		}
		throw new MessageCreationException(ErrorResponseCode.TECHNICAL_ERROR.getDescription(), ErrorResponseCode.TECHNICAL_ERROR);
	}

	public IMetadataService getMetadataService() {
		return metadataService;
	}

	public void setMetadataService(IMetadataService metadataService) {
		this.metadataService = metadataService;
	}

	private static final Logger logger = LoggerFactory.getLogger(MessageService.class);

	/**
	 * Used By ParentDocumentHandlerServiceActivator
	 */
	@Override
	@Transactional(propagation = Propagation.REQUIRED, readOnly = true)
	public Message retrieveMessage(String messageDocumentId, String documentTypeCode, Long senderId, Long receiverId, Boolean biDirectional, Set<String> statesToExclude) {
		List<Message> queryResult = messageDAO.retrieveMessages(messageDocumentId, documentTypeCode, senderId, receiverId, biDirectional, statesToExclude);
		if (queryResult != null && queryResult.size() > 0) {
			// TODO we always return the first
			if (queryResult.size() > 1) {
				logger.warn("Duplicate messages ! id= " + messageDocumentId + ", type=" + documentTypeCode + ", sender=" + senderId + ", receiver=" + receiverId);
			}
			return queryResult.get(0);
		}
		// if nothing available
		return null;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED, readOnly = true)
	public Message retrieveMessage(String messageDocumentId, Long messageSenderId, Long messageTransactionTypeId) {
		return messageDAO.retrieveMessage(messageDocumentId, messageSenderId, messageTransactionTypeId);
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED, readOnly = true)
	public Message retrieveMessage(String messageDocumentId, String documentTypeCode, Long messageSenderId, Long messagereceiverId) {
		return messageDAO.retrieveMessage(messageDocumentId, documentTypeCode, messageSenderId, messagereceiverId);
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED, readOnly = true)
	public Message retrieveMessage(Long messageId) {
		return messageDAO.read(messageId);
	}
	
	@Override
	@Transactional(propagation = Propagation.REQUIRED, readOnly = true)
	public Message retrieveMessageWithBinaries(Long messageId) {
		Message m = messageDAO.read(messageId); 
		if (m != null){
			m.getBinaries();
		}
		return m;
	}
	
	@Override
	@Transactional(propagation = Propagation.REQUIRED, readOnly = true)
	public Message retrieveMessageWithInitializedCollections(Long messageId) {
		Message message = messageDAO.read(messageId);
		Hibernate.initialize(message.getChildMessages());
		Hibernate.initialize(message.getBinaries());
		for (Message child : message.getChildMessages()) {
			Hibernate.initialize(child);
			for (MessageBinary binary : child.getBinaries()) {
				Hibernate.initialize(binary);
			}
		}
		for (MessageBinary binary : message.getBinaries()) {
			Hibernate.initialize(binary);
		}
		return message;
	}

    @Override
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    public Message retrieveMessageWithInitializedProperties(Long messageId) {
        Message message = messageDAO.read(messageId);
        initializeMessageProperties(message);
        initializeMessageCollections(message);

        return message;
    }

	@Override
	public MessageBinary retrieveMessageBinary(Long messageBinaryId) {
		return messageBinaryDAO.read(messageBinaryId);
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED)
	public void deleteMessageBinary(Long binaryId) throws MessageUpdateException {
		messageBinaryDAO.deleteMessageBinary(binaryId);

	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED, readOnly = true)
	public InputStream getMessageBinaryAsStream(Long messageBinaryId) {
		return messageBinaryDAO.getMessageBinaryAsStream(messageBinaryId);
	}

	@Override
	public InputStream getDecryptedStream(InputStream encryptedStream, byte[] iv) throws MessageRetrieveException {
		try {
			return messageBinaryDAO.getDecryptedStream(encryptedStream, iv);
		} catch (IOException e) {
			throw new MessageRetrieveException(e, e.getMessage(), ErrorResponseCode.TECHNICAL_ERROR);
		}
	}

	@Override
	public InputStream getDecryptedStream(Long messageBinayId) throws MessageRetrieveException {
		try {
			MessageBinary bin = messageBinaryDAO.read(messageBinayId);
			FileInputStream encryptedStream = new FileInputStream(new File(bin.getFileId()));
			return messageBinaryDAO.getDecryptedStream(encryptedStream, bin.getIv());
		} catch (IOException e) {
			throw new MessageRetrieveException(e, e.getMessage(), ErrorResponseCode.TECHNICAL_ERROR);
		}
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED)
	public Message updateMessage(Message message) {
		return messageDAO.update(message);
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED)
	public MessageBinary updateMessageBinary(MessageBinary binary) throws MessageUpdateException {
		return messageBinaryDAO.update(binary);
	}

	@Override
	public Long createMessageBinary(CreateMessageBinaryDTO createMessageBinaryDTO) throws MessageCreationException {
		MessageBinary bin = null;
		StringBuffer fullFilePath = null;
		Party senderParty = null;
		try {
			senderParty = authorisationService.getParty(createMessageBinaryDTO.getSenderIdWithScheme(),
					createMessageBinaryDTO.getIssuer().getBusinessDomain());
		} catch (UndefinedIdentifierException e1) {
		}		
		LogDTO logDTO = new LogDTO.LogDTOBuilder(LogDTO.LOG_TYPE.INFO, LogDTO.LOG_OPERATION.CRUD, this.getClass().getName())
			.description("Inside MessageService ")
			.businessDomain(createMessageBinaryDTO.getIssuer().getBusinessDomain())
			.build();
		try {
			PushbackInputStream pBackIS = new PushbackInputStream(createMessageBinaryDTO.getInputStream());
			int b;
			b = pBackIS.read();
			if (b == -1) {
				throw new MessageCreationException(ErrorResponseCode.BINARY_ERROR.getDescription(), ErrorResponseCode.BINARY_ERROR);
			}
			pBackIS.unread(b);
			// check if parameters changed
			reloadFileStoreParameters(false);
			Boolean useFileStore = createMessageBinaryDTO.getUseFileStore();
			if (useFileStore == null)
				useFileStore = defaultUseFileStore;
			if (useFileStore) {
				String delimiter = System.getProperty("file.separator");
				fullFilePath = new StringBuffer();
				String subfolder = "";
				if (createMessageBinaryDTO.getSenderIdWithScheme() != null) {
					subfolder = subfolder.concat(createMessageBinaryDTO.getSenderIdWithScheme().replaceAll(":", "-")).concat(delimiter);
				}
				// get store
				String store = selectStore();

				fullFilePath.append(store).append(delimiter).append(subfolder).append(BASE_FILE_NAME).append("-").append(UUID.randomUUID().toString()).append('.').append(BASE_FILE_EXT);
				createMessageBinaryDTO.setFullFilePath(fullFilePath.toString());
				logger.debug("storing binary to file [{}], encryption [{}]", fullFilePath.toString(), createMessageBinaryDTO.getEncryptBinary());

				// ETRUSTEX-948 enforce 100MB wrapper size limit only if it is
				// not overriden by an SLA policy
				EncryptBinaryDTO encryptBinaryDTO = messageBinaryDAO.storeBinaryStreamToFile(pBackIS, fullFilePath.toString(), createMessageBinaryDTO.getEncryptBinary(), 
						enforceWrapperMaxBinarySizeLimit(senderParty));

				if (createMessageBinaryDTO.getExpectedSize() != null) {

					if (!createMessageBinaryDTO.getExpectedSize().equals(encryptBinaryDTO.getBinarySize())) {
						throw new MessageCreationException(ErrorResponseCode.HARD_BUSINESS_RULE_VIOLATION.getDescription(), ErrorResponseCode.HARD_BUSINESS_RULE_VIOLATION);
					}

				} else {
					createMessageBinaryDTO.setExpectedSize(encryptBinaryDTO.getBinarySize());
				}
				createMessageBinaryDTO.setIv(encryptBinaryDTO.getIv());
				logDTO.setMessageSize(encryptBinaryDTO.getBinarySize());
				logDTO.setSenderParty(senderParty);
				logDTO.setIssuerParty(createMessageBinaryDTO.getIssuer());
				logDTO.setDocumentId(createMessageBinaryDTO.getDocumentId());
				bin = messageBinaryDAO.createMessageBinary(createMessageBinaryDTO);
				logDTO.setMessageBinaryId(bin.getId());
				logDTO.setMessageId(bin.getMessage() != null ? bin.getMessage().getId() : null);
				if (bin != null && bin.getMessage() != null && bin.getMessage().getSender() != null) {
					logDTO.setBusinessDomain(bin.getMessage().getSender().getBusinessDomain());
				}
				logService.saveLog(logDTO);
				updateStats(store, encryptBinaryDTO.getBinarySize());
			} else {
				bin = messageBinaryDAO.createMessageBinary(createMessageBinaryDTO);
				messageBinaryDAO.storeBinaryStreamToDataBase(bin.getId(), pBackIS, createMessageBinaryDTO.getEncryptBinary());
			}
		} catch (MessageCreationException e) {
			logDTO.setLogType(LogDTO.LOG_TYPE.ERROR);
			logDTO.setDescription(logDTO.getDescription() + e.getMessage());
			logService.saveLog(logDTO);
			if (fullFilePath != null) {
				File f = new File(fullFilePath.toString());
				if (f.exists()) {
					f.delete();
				}
			}
			if (bin != null) {
				messageBinaryDAO.deleteMessageBinary(bin.getId());
			}
			throw e;
		} catch (BinaryStreamLimitExceededException e) {
			logDTO.setLogType(LogDTO.LOG_TYPE.ERROR);
			logDTO.setDescription(logDTO.getDescription() + e.getMessage());
			logService.saveLog(logDTO);
			logger.error(e.getMessage(), e);
			if (fullFilePath != null) {
				File f = new File(fullFilePath.toString());
				if (f.exists()) {
					f.delete();
				}
			}
			if (bin != null) {
				messageBinaryDAO.deleteMessageBinary(bin.getId());
			}
			throw new MessageCreationException(e, ErrorResponseCode.SLA_BINARY_SIZE_TOO_LARGE.getDescription(), ErrorResponseCode.SLA_BINARY_SIZE_TOO_LARGE);
		} catch (IOException ioe) {
			throw new MessageCreationException(ioe, ErrorResponseCode.BINARY_ERROR.getDescription(), ErrorResponseCode.BINARY_ERROR);
		} catch (Exception e) {
			logDTO.setLogType(LogDTO.LOG_TYPE.ERROR);
			logDTO.setDescription(logDTO.getDescription() + e.getMessage());
			logService.saveLog(logDTO);
			logger.error(e.getMessage(), e);
			if (fullFilePath != null) {
				File f = new File(fullFilePath.toString());
				if (f.exists()) {
					f.delete();
				}
			}
			if (bin != null) {
				messageBinaryDAO.deleteMessageBinary(bin.getId());
			}
			throw new MessageCreationException(e, ErrorResponseCode.TECHNICAL_ERROR.getDescription(), ErrorResponseCode.TECHNICAL_ERROR);
		}

		return bin.getId();

	}

	/**
	 * check if there are SLA policies that set a higher size limit on wrappers
	 * 
	 * @param senderParty
	 * @return true if there is no policy that sets a higher limit for wrapper
	 *         size, false otherwise
	 */
	private boolean enforceWrapperMaxBinarySizeLimit(Party senderParty) {
		SlaPolicySearchDTO slaPolicySearchDTO = new SlaPolicySearchDTO();
		slaPolicySearchDTO.setSender(senderParty);
		slaPolicySearchDTO.setSlaType(SlaType.SLA_SIZE);
		Transaction transaction = new Transaction();
		transaction.setNamespace(WRAPPER_TRANSACTION_NS);
		transaction.setRequestLocalName(STORE_WRAPPER_TRANSACTION_REQ_LOCAL_NAME);
		List<Transaction> transactions = transactionService.getTransactionsByCriteria(transaction);
		if (CollectionUtils.isNotEmpty(transactions)) {
			slaPolicySearchDTO.setTransaction(transactions.get(0));
		}
		List<SlaPolicy> policies = slaPolicyDAO.getPoliciesByCriteria(slaPolicySearchDTO);
		boolean enforceWrapperMaxBinarySizeLimit = true;
		if (CollectionUtils.isNotEmpty(policies)) {
			for (SlaPolicy policy : policies) {
				if (policy.getValue() * 1024 * 1024 > MessageBinaryDAO.STREAM_MAX_NUMBER_OF_BYTES) {
					enforceWrapperMaxBinarySizeLimit = false;
					break;
				}
			}
		}
		return enforceWrapperMaxBinarySizeLimit;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED)
	public void deleteMessage(Message message) throws MessageUpdateException {
		try {
			Set<MessageBinary> binaries = message.getBinaries();
			Set<File> filesToDelete = new HashSet<>();
			for (MessageBinary messageBinary : binaries) {
				if (messageBinary.getFileId() != null) {
					filesToDelete.add(new File(messageBinary.getFileId()));
				}
			}
			messageDAO.delete(message);
			//ETRUSTEX-1241 delete the files only if no errors during message deletion
			for (File file : filesToDelete) {
				file.delete();
			}
		} catch (Exception e) {
			throw new MessageUpdateException(e, ErrorResponseCode.TECHNICAL_ERROR.getDescription(), ErrorResponseCode.TECHNICAL_ERROR);
		}
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED)
	public void deleteMessages(List<Long> messages) {
		try {
			messageDAO.deleteMessages(messages);
		} catch (Exception e) {
			// throw new MessageUpdateException(e, e.getMessage(),
			// ErrorResponseCode.TECHNICAL_ERROR);
		}
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED)
	public Long createMessage(CreateMessageDTO createMessageDTO) throws MessageCreationException {
		Transaction transaction = authorisationService.getTransactionById(createMessageDTO.getTransactionTypeId());
		Party issuer = partyService.getParty(createMessageDTO.getIssuerId());
		Party sender = partyService.getParty(createMessageDTO.getSenderPartyId());
		LogDTO logDTO = new LogDTO.LogDTOBuilder(LogDTO.LOG_TYPE.INFO, LogDTO.LOG_OPERATION.CRUD, this.getClass().getName())
			.businessCorrelationId(createMessageDTO.getCorrelationId())
			.businessDomain(issuer.getBusinessDomain())
			.description("Inside MessageService")
			.documentId(createMessageDTO.getDocumentId())
			.issuerParty(issuer)
			.module("e-TrustEx")
			.receiverParty(partyService.getParty(createMessageDTO.getReceiverPartyId()))
			.senderParty(sender)
			.transaction(transaction)
			.documentTypeCode(transaction != null ? transaction.getDocument().getDocumentTypeCode() : null)
			.build();

		// find metadata to determine what SOAP fault should be thrown in case
		// of duplicate message id
		Map<MetaDataItemType, MetaDataItem> metadata = metadataService.retrieveMetaData(createMessageDTO.getIcaId(), null, null, null);
		MetaDataItem oldFaultSupportMetadataItem = metadata.get(MetaDataItemType.OLD_FAULT_SUPPORT);
		boolean oldFaultSupport = false;
		if (oldFaultSupportMetadataItem == null) {
			// search for metadata for store document wrapper by sender and
			// transaction
			MetaDataItem criteria = new MetaDataItem();
			criteria.setSender(sender);
			criteria.setTansaction(transaction);
			criteria.setRawItemType(MetaDataItemType.OLD_FAULT_SUPPORT.name());
			List<MetaDataItem> metadataList = metadataService.getMetaDataItemsByCriteria(criteria);
			if (CollectionUtils.isNotEmpty(metadataList)) {
				oldFaultSupportMetadataItem = metadataList.get(0);
			}
		}
		if (oldFaultSupportMetadataItem != null) {
			oldFaultSupport = BooleanUtils.toBoolean(oldFaultSupportMetadataItem.getValue());
		}
		String exceptionDescription = oldFaultSupport ? "Message ID : " + createMessageDTO.getDocumentId() + " Already exists" : ErrorResponseCode.DUPLICATE_ENTITY.getDescription();
		ErrorResponseCode exceptionResponseCode = oldFaultSupport ? ErrorResponseCode.DOCUMENT_ALREADY_EXISTS : ErrorResponseCode.DUPLICATE_ENTITY;

		try {
			if (retrieveMessage(createMessageDTO.getDocumentId(), createMessageDTO.getDocumentTypeCd(), createMessageDTO.getSenderPartyId(), 
					createMessageDTO.getReceiverPartyId(), false, null) != null) {
				throw new MessageCreationException(exceptionDescription, exceptionResponseCode);
			}
			EntityAccessInfo info = new EntityAccessInfo();
			info.setCreationDate(createMessageDTO.getReceptionDate());
			info.setModificationDate(createMessageDTO.getReceptionDate());
			info.setCreationId(createMessageDTO.getAuthenticatedUser());
			info.setModificationId(createMessageDTO.getAuthenticatedUser());
			Message message = null;
			try {
				message = messageDAO.createMessage(buildMessage(createMessageDTO));
			} catch (SQLException e) {

				if (e instanceof SQLIntegrityConstraintViolationException) {
					throw new MessageCreationException(exceptionDescription, exceptionResponseCode);
				} else {
					throw e; // just rethrow
				}
			} catch (PersistenceException e) {

				if (e.getCause() instanceof ConstraintViolationException) {
					// we check if the ConstraintViolation Is caused by the
					// entry of a Duplicate Unique
					// TODO (ode) to be reviewed and tested on MySQL
					if (e.getCause().getMessage().toLowerCase().contains("unique")) {
						exceptionDescription = oldFaultSupport ? "Message ID : " + createMessageDTO.getDocumentId() + " Already exists" : ErrorResponseCode.DUPLICATE_ENTITY.getDescription();
						exceptionResponseCode = oldFaultSupport ? ErrorResponseCode.DOCUMENT_ALREADY_EXISTS : ErrorResponseCode.DUPLICATE_ENTITY;
						throw new MessageCreationException(exceptionDescription, exceptionResponseCode);
					} else {
						throw new MessageCreationException(exceptionDescription, exceptionResponseCode);
					}
				} else {
					throw e;
				}
			}

			Long messageId = message.getId();
			logDTO.setMessageId(messageId);
			if (createMessageDTO.getParentMessageId() != null) {
				Message parent = messageDAO.read(createMessageDTO.getParentMessageId());
				if (parent != null) {
					message.addParentMessage(parent);
				}
			}
			// Long messageId= toStore.getId();
			for (MessageBinary messageBinary : createMessageDTO.getBinaries()) {
				// this is in the normal case
				MessageBinary tosave = null;
				if (messageBinary.getId() == null) {
					tosave = messageBinaryDAO.createMessageBinary(messageId, messageBinary);
					tosave.setAccessInfo(info);
					tosave.setMessage(message);

				}
				// in thecase of a wrapper the binary is srteamed before and we
				// receive the id
				else {
					tosave = messageBinaryDAO.read(messageBinary.getId());
					tosave.setAccessInfo(info);
					tosave.setMessage(message);
				}
				message.addMessageBinary(tosave);
			}
			messageDAO.update(message);
			logService.saveLog(logDTO);
			return messageId;
		} catch (SQLException e) {

			throw new MessageCreationException(e, e.getMessage(), ErrorResponseCode.TECHNICAL_ERROR);
		}
	}
	
	private Message buildMessage(CreateMessageDTO createMessageDTO) {
		Message newMessage = new Message();
		if (createMessageDTO.getIcaId() != null) {
			InterchangeAgreement agreement = new InterchangeAgreement();
			agreement.setId(createMessageDTO.getIcaId());
			newMessage.setAgreement(agreement);
		}
		newMessage.setDocumentId(createMessageDTO.getDocumentId());
		newMessage.setCorrelationId(createMessageDTO.getCorrelationId());
		newMessage.setStatusCode(createMessageDTO.getStatusCode());
		newMessage.setReceptionDate(createMessageDTO.getReceptionDate());
		newMessage.setRetrieveIndicator(false);
		newMessage.setResponseCode(createMessageDTO.getResponseCode());
		Party issuer = new Party();
		issuer.setId(createMessageDTO.getIssuerId());
		newMessage.setIssuer(issuer);
		Transaction t = new Transaction();
		t.setId(createMessageDTO.getTransactionTypeId());
		newMessage.setTransaction(t);
		EntityAccessInfo accessInfo = new EntityAccessInfo();
		accessInfo.setCreationDate(createMessageDTO.getReceptionDate());
		accessInfo.setCreationId(createMessageDTO.getAuthenticatedUser());
		accessInfo.setModificationDate(createMessageDTO.getReceptionDate());
		accessInfo.setModificationId(createMessageDTO.getAuthenticatedUser());
		newMessage.setAccessInfo(accessInfo);
		newMessage.setIssueDate(createMessageDTO.getIssueDate());
		if (createMessageDTO.getReceiverPartyId() != null) {
			Party receiver = new Party();
			receiver.setId(createMessageDTO.getReceiverPartyId());
			newMessage.setReceiver(receiver);
		}
		Party sender = new Party();
		sender.setId(createMessageDTO.getSenderPartyId());
		newMessage.setSender(sender);
		newMessage.setMessageDocumentTypeCode(createMessageDTO.getDocumentTypeCd());
		return newMessage;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED, readOnly = true)
	public List<Message> retrieveMessages(Long receiverPartyId, Long senderPartyId, Long issuerPartyId, Long icaId, Set<Transaction> transactions, Integer maxResult, String messageDocumentId,
			String documentTypeCode, Boolean retrievedInd, Boolean fetchParents, Boolean fetchBinaries, Boolean filterOutMessagesInError) {
		return messageDAO.retrieveMessages(receiverPartyId, senderPartyId, issuerPartyId, icaId, transactions, maxResult, messageDocumentId, documentTypeCode, retrievedInd, fetchParents,
				fetchBinaries, filterOutMessagesInError);
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED)
	public void updateMessage(Long messageId, String statusCode, String responseCode) {
		Message m = messageDAO.read(messageId);
		m.setResponseCode(responseCode);
		m.setStatusCode(statusCode);
		m.getAccessInfo().setModificationDate(Calendar.getInstance().getTime());
		messageDAO.update(m);
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED)
	public void updateMessageStatus(Long messageId, String statusCode) {
		Message m = messageDAO.read(messageId);
		m.setStatusCode(statusCode);
		m.getAccessInfo().setModificationDate(Calendar.getInstance().getTime());
		messageDAO.update(m);
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED, readOnly = true)
	public String getMessageBinaryAsString(Long messageId, MessageBinaryType type) {
		return messageBinaryDAO.getMessageBinaryAsString(messageId, type);
	}

	public IMessageDAO getMessageDAO() {
		return messageDAO;
	}

	public void setMessageDAO(IMessageDAO messageDAO) {
		this.messageDAO = messageDAO;
	}

	public IMessageBinaryDAO getMessageBinaryDAO() {
		return messageBinaryDAO;
	}

	public void setMessageBinaryDAO(IMessageBinaryDAO messageBinaryDAO) {
		this.messageBinaryDAO = messageBinaryDAO;
	}

	@Override
	public List<Message> retrieveMessages(Set<Long> receiverPartyIds, Set<Long> senderPartyIds, Long issuerPartyId, Set<Transaction> transactions, Set<String> documentTypeCodes, String documentId,
			Boolean retrievedInd, Date startDate, Date endDate, String correlationId, Integer maxResult, Boolean fetchParents, Boolean fetchBinaries, Boolean isSenderAlsoReceiver,
			Boolean filterOutMessagesInError, Boolean hasParents) {

		Set<String> correlationIds = null;
		if (correlationId != null) {
			correlationIds = new HashSet<String>();
			correlationIds.add(correlationId);
		}
		return messageDAO.retrieveMessages(receiverPartyIds, senderPartyIds, null, null, issuerPartyId, transactions, documentTypeCodes, documentId, retrievedInd, startDate, endDate, correlationIds,
				maxResult, fetchParents, fetchBinaries, isSenderAlsoReceiver, filterOutMessagesInError, hasParents);
	}

	@Override
	@Transactional(propagation = Propagation.SUPPORTS)
	public List<Message> retrieveLeaves(BusinessDomain businessDomain, Transaction transaction, Date startDate, Date endDate, Set<Transaction> childTransactionsToIgnore) {
		return messageDAO.retrieveLeaves(businessDomain, transaction, startDate, endDate, childTransactionsToIgnore);
	}

	@Override
	@Transactional(propagation = Propagation.SUPPORTS)
	public List<Message> retrieveOrphans(BusinessDomain businessDomain, Transaction transaction, Date startDate, Date endDate) {
		return messageDAO.retrieveOrphans(businessDomain, transaction, startDate, endDate);
	}

	// @Override
	// public MessagesListVO retrieveMessagesWithConversation(Set<Long>
	// receiverPartyIds,
	// Set<Long> senderPartyIds, Set<Long> receiverPartyIds2, Set<Long>
	// senderPartyIds2, Long issuerPartyId,
	// Set<Transaction> transactions, Set<String> documentTypeCodes,String
	// documentId,
	// Boolean retrievedInd,Date startDate,Date endDate,String correlationId,
	// Integer maxResult, Boolean fetchParents,
	// Boolean fetchBinaries,Boolean isSenderAlsoReceiver, String userId,
	// Set<String> businessDocumentType, BigDecimal paginationFrom, BigDecimal
	// paginationTo, SortFieldTypeCode sortField,Boolean
	// filterOutMessagesInError) {
	// Set<String> correlationIds = new HashSet<String>();
	// if(correlationId != null && correlationId!=null){
	// correlationIds.add(correlationId);
	// }
	//
	// // Normal Query
	// if(startDate == null && endDate == null){
	// if(paginationFrom != null && paginationTo != null){
	// Set<String> convIds =
	// messageDAO.retrieveConversationIds(receiverPartyIds, senderPartyIds,
	// receiverPartyIds2, senderPartyIds2, issuerPartyId, transactions,
	// documentTypeCodes,documentId, retrievedInd, startDate, endDate,
	// correlationIds, maxResult, fetchParents,
	// fetchBinaries,isSenderAlsoReceiver, userId, businessDocumentType, null,
	// null, sortField,filterOutMessagesInError);
	// convIds.remove(null);
	// int size = convIds.size();
	// int start = paginationFrom.intValue() -1;
	// int end = paginationTo.intValue();
	// List<String> list = new ArrayList<String>();
	// list.addAll(convIds);
	// Set<String> pagedConv = new
	// LinkedHashSet<String>(list.subList(start<=size?start:size,
	// end<=size?end:size));
	// MessagesListVO mlvo =
	// messageDAO.retrieveMessagesJustice(receiverPartyIds, senderPartyIds,
	// receiverPartyIds2, senderPartyIds2, issuerPartyId, transactions,
	// documentTypeCodes,documentId, retrievedInd, startDate, endDate,
	// pagedConv, maxResult, fetchParents, fetchBinaries,isSenderAlsoReceiver,
	// userId, businessDocumentType, null, null,
	// sortField,filterOutMessagesInError);
	// mlvo.setSize(size);
	// //TODO throw an exception if convIds.size() > 50
	// return mlvo;
	// }else{
	// return messageDAO.retrieveMessagesJustice(receiverPartyIds,
	// senderPartyIds, receiverPartyIds2, senderPartyIds2, issuerPartyId,
	// transactions, documentTypeCodes,documentId, retrievedInd, startDate,
	// endDate, correlationIds, maxResult, fetchParents,
	// fetchBinaries,isSenderAlsoReceiver, userId, businessDocumentType, null,
	// null, sortField,filterOutMessagesInError);
	// }
	//
	// }
	// // Retrieve all conversations contained within the time range
	// // IMPORTANT NOTE: When date range is present, since we're taking out the
	// filter on UserID and using only conversationID as a search criteria:
	// // Results that have no conversationID will not be retrieved otherwise
	// all conversations without correlationID will be pulled.
	// else{
	// List<Message> firstList =
	// messageDAO.retrieveMessagesJustice(receiverPartyIds, senderPartyIds,
	// receiverPartyIds2, senderPartyIds2,issuerPartyId, transactions,
	// documentTypeCodes,documentId, retrievedInd, startDate, endDate,
	// correlationIds, maxResult, fetchParents,
	// fetchBinaries,isSenderAlsoReceiver, userId, businessDocumentType, null,
	// null, null,filterOutMessagesInError).getMessages();
	// if(firstList != null && !firstList.isEmpty()){
	// for (Message message : firstList) {
	// if(message.getCorrelationId() != null &&
	// !correlationIds.contains(message.getCorrelationId())){
	// correlationIds.add(message.getCorrelationId());
	// }
	// }
	// if(!correlationIds.isEmpty()){
	// //TODO throw an exception if correlationIds.size() > 50
	// return messageDAO.retrieveMessagesJustice(null, null, null, null, null,
	// null, documentTypeCodes,null, retrievedInd, null, null, correlationIds,
	// maxResult, fetchParents, fetchBinaries,false, userId,
	// businessDocumentType, null, null, sortField,filterOutMessagesInError);
	// }
	// }
	// }
	// return new MessagesListVO(new ArrayList<Message>(),0);
	// }
	@Override
	public MessagesListVO retrieveMessagesWithConversation(Set<Long> receiverPartyIds, Set<Long> senderPartyIds, Set<Long> receiverPartyIds2, Set<Long> senderPartyIds2, Long issuerPartyId,
			Set<Transaction> transactions, Set<String> documentTypeCodes, String documentId, Boolean retrievedInd, Date startDate, Date endDate, String correlationId, Integer maxResult,
			Boolean fetchParents, Boolean fetchBinaries, Boolean isSenderAlsoReceiver, String userId, Set<String> businessDocumentType, BigDecimal paginationFrom, BigDecimal paginationTo,
			SortFieldTypeCode sortField, Boolean filterOutMessagesInError) {
		Set<String> correlationIds = new HashSet<String>();
		if (correlationId != null) {
			correlationIds.add(correlationId);
		}
		MessagesListVO mlvo = messageDAO.retrieveMessagesJustice(receiverPartyIds, senderPartyIds, receiverPartyIds2, senderPartyIds2, issuerPartyId, transactions, documentTypeCodes, documentId,
				retrievedInd, startDate, endDate, correlationIds, maxResult, fetchParents, fetchBinaries, isSenderAlsoReceiver, userId, businessDocumentType, paginationFrom, paginationTo, sortField,
				filterOutMessagesInError);
		// TODO throw an exception if convIds.size() > 50
		return mlvo;
		// return new MessagesListVO(new ArrayList<Message>(),0);
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED, readOnly = true)
	public List<Message> findMessagesByTransaction(Long transactionId) {
		return messageDAO.findMessagesByTransaction(transactionId);
	}

    @Override
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    public List<String> getDocumentTypeCodes() {
        return messageDAO.getDocumentTypeCodes();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    public List<String> getStatusCodes() {
        return messageDAO.getStatusCodes();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    public List<Message> findMessagesByCriteria(Message message, Date createdFrom, Date createdTo, int firstResult, int maxResults, List<Long> businessDomainIds, DispatchEnum dispatched) {
        List<Message> messages = messageDAO.findMessagesByCriteria(message, createdFrom, createdTo, firstResult, maxResults, businessDomainIds, dispatched);

        for(Message m : messages) {
            initializeMessageProperties(m);
        }

        return messages;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    public long countMessagesByCriteria(Message message, Date createdFrom, Date createdTo, List<Long> businessDomainIds, DispatchEnum dispatched) {
        return messageDAO.countMessagesByCriteria(message, createdFrom, createdTo, businessDomainIds, dispatched);
    }

    public Boolean getLocalUsefilestore() {
		return localUsefilestore;
	}

	public void setLocalUsefilestore(Boolean localUsefilestore) {
		this.localUsefilestore = localUsefilestore;
	}

	public Boolean getUsefilestoreMetadata() {
		return usefilestoreMetadata;
	}

	public void setUsefilestoreMetadata(Boolean usefilestoreMetadata) {
		this.usefilestoreMetadata = usefilestoreMetadata;
	}

	public String getLocalFileStorePath() {
		return localFileStorePath;
	}

	public void setLocalFileStorePath(String localFileStorePath) {
		this.localFileStorePath = localFileStorePath;
	}

	public IAuthorisationService getAuthorisationService() {
		return authorisationService;
	}

	public void setAuthorisationService(IAuthorisationService authorisationService) {
		this.authorisationService = authorisationService;
	}

    private void initializeMessageProperties(Message message) {
        Hibernate.initialize(message.getIssuer());
        Hibernate.initialize(message.getSender());
        Hibernate.initialize(message.getReceiver());
        Hibernate.initialize(message.getTransaction());
        Hibernate.initialize(message.getAgreement());
    }

    private void initializeMessageCollections(Message message) {
        for (Message child : message.getChildMessages()) {
            Hibernate.initialize(child);
            Hibernate.initialize(child.getSender().getBusinessDomain());
            for (MessageBinary binary : child.getBinaries()) {
                Hibernate.initialize(binary);
            }
        }

        for (Message parent : message.getParentMessages()) {
            Hibernate.initialize(parent);
            Hibernate.initialize(parent.getSender().getBusinessDomain());
            for (MessageBinary binary : parent.getBinaries()) {
                Hibernate.initialize(binary);
            }
        }

        for (MessageBinary binary : message.getBinaries()) {
            Hibernate.initialize(binary);
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void detachParent(Long messageId) {
    	Message message = retrieveMessage(messageId);
		message.setParentMessages(null);
		updateMessage(message);
    }
    
    @Override
    public boolean isManaged(Message message) {
    	return messageDAO.isManaged(message);
    }
}