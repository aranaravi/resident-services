package io.mosip.registration.dao.impl;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.registration.dao.JobConfigDAO;
import io.mosip.registration.entity.SyncJob;
import io.mosip.registration.exception.RegBaseUncheckedException;
import io.mosip.registration.repositories.JobConfigRepository;

/**
 * implementation class of {@link JobConfigDAO}
 * 
 * @author Dinesh Ashokan
 *
 */
@Repository
public class JobConfigDAOImpl implements JobConfigDAO {

	@Autowired
	private JobConfigRepository jobConfigRepository;

	/** Object for Logger. */
	private static Logger LOGGER;

	/*
	 * (non-Javadoc)
	 * 
	 * @see io.mosip.registration.dao.JobConfigDAO#getJob()
	 */
	@Override
	public List<SyncJob> getAll() {
		return jobConfigRepository.findAll();
	}

	@Override
	public List<SyncJob> getActiveJobs() {
		return jobConfigRepository.findByIsActiveTrue();
	}

}
