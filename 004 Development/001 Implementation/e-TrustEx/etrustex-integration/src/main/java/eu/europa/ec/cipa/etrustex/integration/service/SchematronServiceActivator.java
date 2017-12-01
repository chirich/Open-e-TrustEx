package eu.europa.ec.cipa.etrustex.integration.service;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;

import net.sf.saxon.Configuration;
import net.sf.saxon.om.DocumentInfo;
import net.sf.saxon.om.Item;
import net.sf.saxon.om.SequenceIterator;
import net.sf.saxon.query.DynamicQueryContext;
import net.sf.saxon.query.StaticQueryContext;
import net.sf.saxon.query.XQueryExpression;
import net.sf.saxon.trans.XPathException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.Message;
import org.springframework.integration.support.MessageBuilder;

import eu.europa.ec.cipa.etrustex.domain.MessageBinary;
import eu.europa.ec.cipa.etrustex.domain.Transaction;
import eu.europa.ec.cipa.etrustex.domain.util.EntityAccessInfo;
import eu.europa.ec.cipa.etrustex.domain.util.MetaDataItem;
import eu.europa.ec.cipa.etrustex.integration.IMessageProcessorActivator;
import eu.europa.ec.cipa.etrustex.integration.exception.MessageProcessingException;
import eu.europa.ec.cipa.etrustex.integration.message.TrustExMessage;
import eu.europa.ec.cipa.etrustex.services.dto.LogDTO;
import eu.europa.ec.cipa.etrustex.types.ErrorResponseCode;
import eu.europa.ec.cipa.etrustex.types.MessageBinaryType;
import eu.europa.ec.cipa.etrustex.types.MetaDataItemType;
import freemarker.template.TemplateException;

public class SchematronServiceActivator extends XmlProcessingServiceActivator
		implements IMessageProcessorActivator {
	final Configuration config = new Configuration();
	final StaticQueryContext sqc = config.newStaticQueryContext();
	private final static String ASSERTION_FAILED_TEMPLATE = "templates/failedSchematronAssertion.flt";
	private static final Logger logger = LoggerFactory
			.getLogger(SchematronServiceActivator.class);

	private String extractSchematronResults(XQueryExpression extractQuery,
			DynamicQueryContext context) throws XPathException, IOException,
			TemplateException {
//		freemarkerConfig.setObjectWrapper(new DefaultObjectWrapper());
//		freemarkerConfig.setClassForTemplateLoading(this.getClass(), "/");
//		Template temp = freemarkerConfig.getTemplate(ASSERTION_FAILED_TEMPLATE);
//		Map<String, String> root = new HashMap<String, String>();
		Map<String, String> codes= metadataService.retrieveResponseCodeValues(null, null, null, null);
		final SequenceIterator<Item> iter = extractQuery.iterator(context);
		StringWriter writer = new StringWriter();
		while (true) {
			Item item = iter.next();
			if (item == null) {
				break;
			}
			String itemText = item.getStringValue().trim();
			String codeText = codes.get(itemText);
			String textToWrite;
			if (codeText != null ) {
				textToWrite = codeText;
			} else {
				logger.error("description not found for response code [{}], using response code as description", itemText);
				textToWrite = itemText; 
			}
			writer.write(textToWrite +',');
//			root.put("assertion", item.getStringValue().trim());
//			temp.process(root, writer);
		}
		return writer.toString();
	}

	@Override
	public Message<TrustExMessage<String>> processASynchMessage(
			Message<TrustExMessage<String>> message) {
		Map<MetaDataItemType, MetaDataItem> metadata = message.getPayload()
				.getHeader().getMetadata();
		MetaDataItem schematronItem = metadata
				.get(MetaDataItemType.DOCUMENT_SCEMATRON);
		MetaDataItem schematronURLItem = metadata
				.get(MetaDataItemType.DOCUMENT_SCEMATRON_URL);
		Source schematronSource = getStreamSource(schematronItem,
				schematronURLItem);
		String transformationResult = null;
		
		LogDTO logDTO = logServiceHelper.createLog(message.getPayload(), LogDTO.LOG_TYPE.ERROR, LogDTO.LOG_OPERATION.VALIDATION, 
				"Inside SchematronServiceActivator", 
				this.getClass().getName());	
		
		try {
			if (schematronSource != null) {
				//transformationResult = XML result of schematron validation
				transformationResult = transform(message.getPayload(),
						schematronSource);
			}
			if (transformationResult != null) {
				XQueryExpression errorQuery;
				DocumentInfo docInfo = config.buildDocument(new StreamSource(
						new StringReader(transformationResult)));
				final DynamicQueryContext dynamicContext = new DynamicQueryContext(
						config);
				dynamicContext.setContextItem(docInfo);
				errorQuery = sqc
						.compileQuery("/*:schematron-output/*:failed-assert[@flag='error']");
				List result = errorQuery.evaluate(dynamicContext);
				eu.europa.ec.cipa.etrustex.domain.Message msgDB = messageService.retrieveMessageWithBinaries(message.getPayload().getHeader().getMessageId());
				if (result != null && result.size() > 0) {
					String errors = extractSchematronResults(errorQuery,
							dynamicContext);
					// for error application responses the sender and receiver
					// are inverted in case of invoice FI error the customer
					// "sends" an error to the supplier
					logDTO.setDescription(logDTO.getDescription() + "Schematron validation error: Hard business rule violated");
					logService.saveLog(logDTO);										
					String appResponseId = message.getPayload().getHeader().getMessageDocumentId()+"_"+msgDB.getMessageDocumentTypeCode()+"_ERR";
					createApplicationResponse(message.getPayload(),appResponseId,ErrorResponseCode.DOCUMENT_VALIDATION_FAILED.getCode(),errors, message.getPayload().getHeader().getReplyTo());
					return null;
				}
				XQueryExpression warningQuery = sqc
						.compileQuery("/*:schematron-output/*:failed-assert[@flag='warning']");
				String warnings = extractSchematronResults(warningQuery,
						dynamicContext);
				if (warnings != null || !warnings.isEmpty()) {
					logger.info("Schematron validation ended with the following warnigs : "
							+ warnings);
					MessageBinary schematronResultsBinary = new MessageBinary();
					msgDB.addMessageBinary(schematronResultsBinary);
					schematronResultsBinary.setBinary(transformationResult.getBytes("UTF-8"));
					schematronResultsBinary.setBinaryType(MessageBinaryType.SCHEMATRON_RESULT.name());
					schematronResultsBinary.setMimeCode("text/xml");
					schematronResultsBinary.setSize(Long.valueOf(transformationResult.getBytes("UTF-8").length));
					EntityAccessInfo info = new EntityAccessInfo();
					info.setCreationDate(new Date());
					info.setCreationId("TRUSTEX");
					schematronResultsBinary.setAccessInfo(info);
//					messageService.updateMessage(msgDB);
					
				}
				message.getPayload().getHeader().setSchematronResult(warnings);
			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			logDTO.setDescription(logDTO.getDescription() + " " + e.getMessage());
			logService.saveLog(logDTO);
			errorChannel.send(message);
			return null;
		}
		MessageBuilder<TrustExMessage<String>> builder = MessageBuilder
				.withPayload(message.getPayload()).copyHeaders(
						message.getHeaders());
		return builder.build();
	}

	@Override
	public Message<TrustExMessage> processSynchMessage (Message<TrustExMessage<String>> message) {
		Map<MetaDataItemType, MetaDataItem> metadata = message.getPayload()
				.getHeader().getMetadata();
		MetaDataItem schematronItem = metadata
				.get(MetaDataItemType.DOCUMENT_SCEMATRON);
		MetaDataItem schematronURLItem = metadata
				.get(MetaDataItemType.DOCUMENT_SCEMATRON_URL);
		Source schematronSource = getStreamSource(schematronItem,
				schematronURLItem);
		Transaction t = authorisationService.getTransactionById(message
				.getPayload().getHeader().getTransactionTypeId());
		String transformationResult = null;
		String schematronErrors = null;

		LogDTO logDTO = logServiceHelper.createLog(message.getPayload(), LogDTO.LOG_TYPE.ERROR, LogDTO.LOG_OPERATION.VALIDATION, 
				"Inside SchematronServiceActivator", 
				this.getClass().getName());
		
		try {
			if (schematronSource != null) {
				transformationResult = transform(message.getPayload(),
						schematronSource);
			}
			if (transformationResult != null) {
				XQueryExpression errorQuery;
					DocumentInfo docInfo = config.buildDocument(new StreamSource(
							new StringReader(transformationResult)));
					final DynamicQueryContext dynamicContext = new DynamicQueryContext(
							config);
					dynamicContext.setContextItem(docInfo);
					errorQuery = sqc
							.compileQuery("/*:schematron-output/*:failed-assert[@flag='error']");
					List result = errorQuery.evaluate(dynamicContext);
					if (result != null && result.size() > 0) {
						schematronErrors = extractSchematronResults(errorQuery,
								dynamicContext);
					}
			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			logDTO.setDescription(logDTO.getDescription() + " " + e.getMessage());
			logService.saveLog(logDTO);
			throw new MessageProcessingException("soapenv:Server", ErrorResponseCode.TECHNICAL_ERROR.getDescription(), null,
					ErrorResponseCode.TECHNICAL_ERROR, null, null);
		}
		if (schematronErrors != null) {
			throw new MessageProcessingException("soapenv:Client", ErrorResponseCode.HARD_BUSINESS_RULE_VIOLATION.getDescription(), null,
					ErrorResponseCode.HARD_BUSINESS_RULE_VIOLATION, null, null);
		}
		MessageBuilder<TrustExMessage> builder = MessageBuilder.withPayload(
				(TrustExMessage) message.getPayload()).copyHeaders(
				message.getHeaders());
		return builder.build();
	}

}