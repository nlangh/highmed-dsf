package org.highmed.dsf.bpe.dao;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.highmed.dsf.bpe.process.ProcessKeyAndVersion;
import org.highmed.dsf.bpe.process.ProcessesResource;
import org.highmed.dsf.bpe.process.ResourceInfo;

public interface ProcessPluginResourcesDao
{
	List<ResourceInfo> getResources(ProcessKeyAndVersion processKeyAndVersion) throws SQLException;

	Map<ProcessKeyAndVersion, List<ResourceInfo>> getResources() throws SQLException;

	void addOrRemoveResources(Collection<? extends ProcessesResource> newResources, List<UUID> deletedResourcesIds)
			throws SQLException;
}
