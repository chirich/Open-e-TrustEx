package eu.europa.ec.cipa.etrustex.services.dao;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaBuilder.Trimspec;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.sql.DataSource;

import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import eu.europa.ec.cipa.etrustex.domain.Document_;
import eu.europa.ec.cipa.etrustex.domain.InterchangeAgreement_;
import eu.europa.ec.cipa.etrustex.domain.Party_;
import eu.europa.ec.cipa.etrustex.domain.Profile;
import eu.europa.ec.cipa.etrustex.domain.Profile_;
import eu.europa.ec.cipa.etrustex.domain.Transaction_;
import eu.europa.ec.cipa.etrustex.domain.util.MetaDataItem;
import eu.europa.ec.cipa.etrustex.domain.util.MetaDataItem_;

public class MetadataDAO extends TrustExDAO<MetaDataItem, Long> implements IMetadataDAO {
	
	@Autowired
	private DataSource eTrustExDS;
	

	@Override
	public List<MetaDataItem> getTransactionMetadata(Long transactionId) {
	  
		return entityManager.createQuery("from eu.europa.ec.cipa.etrustex.domain.util.MetaDataItem m where m.tansaction.id =:transactionId",MetaDataItem.class)
		.setParameter("transactionId", transactionId).getResultList();
	}

	@Override
	public List<MetaDataItem> getDocumentMetadata(Long documentId) {
		return entityManager.createQuery("from eu.europa.ec.cipa.etrustex.domain.util.MetaDataItem m where m.document.id =:documentId",MetaDataItem.class)
		.setParameter("documentId", documentId).getResultList();
	}
	
	@Override
	public List<MetaDataItem> getProfileMetadata(Long profileId) {
		return entityManager.createQuery("from eu.europa.ec.cipa.etrustex.domain.util.MetaDataItem m where m.profile.id =:profileId",MetaDataItem.class)
		.setParameter("profileId", profileId).getResultList();
	}
	
	
	@Override
	public List<MetaDataItem> getInterchangeAgreementMetadata(Long icaId) {
		return entityManager.createQuery("from eu.europa.ec.cipa.etrustex.domain.util.MetaDataItem m where m.interchangeAgreement.id =:icaId",MetaDataItem.class)
		.setParameter("icaId", icaId).getResultList();
	}
	
	@Override
	public List<MetaDataItem> getProfileMetadata(Set<Profile> profiles) {
		return entityManager.createQuery("from eu.europa.ec.cipa.etrustex.domain.util.MetaDataItem m where m.profile in :profiles",MetaDataItem.class)
		.setParameter("profiles", profiles).getResultList();
	}
	
	@Override
	public List<MetaDataItem> getDefaultMetadata() {
		return entityManager.createQuery("from eu.europa.ec.cipa.etrustex.domain.util.MetaDataItem m where m.interchangeAgreement.id is NULL "
				+ "and m.document.id is NULL and m.tansaction.id is NULL and m.profile.id is NULL and m.sender.id is NULL", MetaDataItem.class).getResultList();
	}
	
	@Override
	public List<MetaDataItem> getDefaultMetadataByType(String type) {
		return entityManager.createQuery("from eu.europa.ec.cipa.etrustex.domain.util.MetaDataItem m where m.interchangeAgreement.id is NULL and "
				+ "m.document.id is NULL and m.tansaction.id is NULL and m.profile.id is NULL and m.sender.id is NULL and m.rawItemType = :type", MetaDataItem.class)
				.setParameter("type", type).getResultList();
	}
	

	@Override
	public List<MetaDataItem> getMetaDataItemsByCriteria(MetaDataItem metaDataItem) {
		CriteriaBuilder cb = entityManager.getCriteriaBuilder();
		CriteriaQuery<MetaDataItem> cq = cb.createQuery(MetaDataItem.class);
		Root<MetaDataItem> mi = cq.from(MetaDataItem.class);
		List<Predicate> predicates = new ArrayList<Predicate>();
		
		if(StringUtils.isNotEmpty(metaDataItem.getRawItemType())) {
			predicates.add(cb.equal(cb.trim(Trimspec.BOTH, cb.upper(mi.get(MetaDataItem_.rawItemType))), metaDataItem.getRawItemType().trim().toUpperCase()));
		}
		
		if(metaDataItem.getDocument() != null && metaDataItem.getDocument().getId() != null) {
			predicates.add(cb.equal(mi.get(MetaDataItem_.document).get(Document_.id), metaDataItem.getDocument().getId()));
		}
		
		if(metaDataItem.getInterchangeAgreement() != null && metaDataItem.getInterchangeAgreement().getId() != null) {
			predicates.add(cb.equal(mi.get(MetaDataItem_.interchangeAgreement).get(InterchangeAgreement_.id), metaDataItem.getInterchangeAgreement().getId()));
		}
		
		if(metaDataItem.getProfile() != null && metaDataItem.getProfile().getId() != null) {
			predicates.add(cb.equal(mi.get(MetaDataItem_.profile).get(Profile_.id), metaDataItem.getProfile().getId()));
		}
		
		if(metaDataItem.getTansaction() != null && metaDataItem.getTansaction().getId() != null) {
			predicates.add(cb.equal(mi.get(MetaDataItem_.tansaction).get(Transaction_.id), metaDataItem.getTansaction().getId()));
		}
		
		if(metaDataItem.getSender() != null && metaDataItem.getSender().getId() != null) {
			predicates.add(cb.equal(mi.get(MetaDataItem_.sender).get(Party_.id), metaDataItem.getSender().getId()));
		}		
				
		cq.select(mi);
		cq.where(predicates.toArray(new Predicate[predicates.size()]));
		cq.orderBy(cb.asc(cb.lower(mi.get(MetaDataItem_.rawItemType))));
		
		return entityManager.createQuery(cq).getResultList();
	}
	
	@Override
	public InputStream getMetadataResourceAsStream(Long metadataItemId) {
		ResultSet rs =null;
		PreparedStatement stmt=null;
		Connection conn=null;
		try {
			conn = eTrustExDS.getConnection();
			String sql = "SELECT MD_VALUE FROM ETR_TB_METADATA where MD_ID=?";
			 stmt = conn.prepareStatement(sql);
			stmt.setLong(1,metadataItemId);
			 rs = stmt.executeQuery();
			if (rs.next()){
				return rs.getBinaryStream(1);
			}
		}catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			if (rs != null) {
				try {
					rs.close();
				} catch (SQLException e) {
				}
			}
			if (stmt != null) {
				try {
					stmt.close();
				} catch (SQLException e) {
				}
			}
			if (conn != null) {
				try {
					conn.close();
				} catch (SQLException e) {
				}
			}
		}
		return null;
	}
	
	public DataSource geteTrustExDS() {
		return eTrustExDS;
	}

	public void seteTrustExDS(DataSource eTrustExDS) {
		this.eTrustExDS = eTrustExDS;
	}
}