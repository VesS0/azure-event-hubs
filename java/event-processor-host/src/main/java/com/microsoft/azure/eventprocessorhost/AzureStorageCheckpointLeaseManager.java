/*
 * Copyright (c) Microsoft. All rights reserved.
 * Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */

// BLAH

package com.microsoft.azure.eventprocessorhost;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Level;

import com.google.gson.Gson;
import com.microsoft.azure.storage.AccessCondition;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageErrorCodeStrings;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.StorageExtendedErrorInformation;
import com.microsoft.azure.storage.blob.BlobRequestOptions;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlobDirectory;
import com.microsoft.azure.storage.blob.CloudBlockBlob;
import com.microsoft.azure.storage.blob.LeaseState;


public class AzureStorageCheckpointLeaseManager implements ICheckpointManager, ILeaseManager
{
    private EventProcessorHost host;
    private String storageConnectionString;
    
    private CloudBlobClient storageClient;
    private CloudBlobContainer eventHubContainer;
    private CloudBlobDirectory consumerGroupDirectory;
    
    private Gson gson;
    
    private final static int storageMaximumExecutionTimeInMs = 2 * 60 * 1000; // two minutes
    private final static int leaseIntervalInSeconds = 30;
    private final static int leaseRenewIntervalInMilliseconds = 10 * 1000; // ten seconds
    private final BlobRequestOptions renewRequestOptions = new BlobRequestOptions();

    public AzureStorageCheckpointLeaseManager(String storageConnectionString)
    {
        this.storageConnectionString = storageConnectionString;
    }

    // The EventProcessorHost can't pass itself to the AzureStorageCheckpointLeaseManager constructor
    // because it is still being constructed. Do other initialization here also because it might throw and
    // hence we don't want it in the constructor.
    public void initialize(EventProcessorHost host) throws InvalidKeyException, URISyntaxException, StorageException
    {
        this.host = host;
        
        this.storageClient = CloudStorageAccount.parse(this.storageConnectionString).createCloudBlobClient();
        BlobRequestOptions options = new BlobRequestOptions();
        options.setMaximumExecutionTimeInMs(AzureStorageCheckpointLeaseManager.storageMaximumExecutionTimeInMs);
        this.storageClient.setDefaultRequestOptions(options);
        
        this.eventHubContainer = this.storageClient.getContainerReference(this.host.getEventHubPath());
        
        this.consumerGroupDirectory = this.eventHubContainer.getDirectoryReference(this.host.getConsumerGroupName());
        
        //this.eventHubInfoBlob = this.eventHubContainer.getBlockBlobReference(AzureStorageCheckpointLeaseManager.eventHubInfoBlobName);
        
        this.gson = new Gson();

        // The only option that .NET sets on renewRequestOptions is ServerTimeout, which doesn't exist in Java equivalent.
        // So right now renewRequestOptions is completely default, but keep it around in case we need to change something later.
    }

    
    //
    // In this implementation, checkpoints are data that's actually in the lease blob, so checkpoint operations
    // turn into lease operations under the covers.
    //
    
    @Override
    public Future<Boolean> checkpointStoreExists()
    {
        return leaseStoreExists();
    }

    @Override
    public Future<Boolean> createCheckpointStoreIfNotExists()
    {
        return createLeaseStoreIfNotExists();
    }

    @Override
    public Future<Checkpoint> getCheckpoint(String partitionId)
    {
        return EventProcessorHost.getExecutorService().submit(() -> getCheckpointSync(partitionId));
    }
    
    private Checkpoint getCheckpointSync(String partitionId) throws URISyntaxException, IOException, StorageException
    {
    	AzureBlobLease lease = getLeaseSync(partitionId);
    	Checkpoint checkpoint = new Checkpoint(partitionId);
    	checkpoint.setOffset(lease.getOffset());
    	checkpoint.setSequenceNumber(lease.getSequenceNumber());
    	return checkpoint;
    }

    @Override
    public Future<Checkpoint> createCheckpointIfNotExists(String partitionId)
    {
        return EventProcessorHost.getExecutorService().submit(() -> createCheckpointIfNotExistsSync(partitionId));
    }
    
    private Checkpoint createCheckpointIfNotExistsSync(String partitionId) throws URISyntaxException, IOException, StorageException
    {
    	// Normally the lease will already be created, checkpoint store is initialized after lease store.
    	AzureBlobLease lease = createLeaseIfNotExistsSync(partitionId);
    	Checkpoint checkpoint = new Checkpoint(partitionId);
    	checkpoint.setOffset(lease.getOffset());
    	checkpoint.setSequenceNumber(lease.getSequenceNumber());
    	return checkpoint;
    }

    @Override
    public Future<Void> updateCheckpoint(Checkpoint checkpoint)
    {
    	return updateCheckpoint(checkpoint, checkpoint.getOffset(), checkpoint.getSequenceNumber());
    }
    
    @Override
    public Future<Void> updateCheckpoint(Checkpoint checkpoint, String offset, long sequenceNumber)
    {
        return EventProcessorHost.getExecutorService().submit(() -> updateCheckpointSync(checkpoint, offset, sequenceNumber));
    }
    
    private Void updateCheckpointSync(Checkpoint checkpoint, String offset, long sequenceNumber) throws Exception
    {
    	// Need to fetch the most current lease data so that we can update it correctly.
    	AzureBlobLease lease = getLeaseSync(checkpoint.getPartitionId());
    	this.host.logWithHostAndPartition(Level.FINE, checkpoint.getPartitionId(), "Checkpointing at " + offset + " // " + sequenceNumber);
    	lease.setOffset(offset);
    	lease.setSequenceNumber(sequenceNumber);
    	updateLeaseSync(lease);
    	return null;
    }

    @Override
    public Future<Void> deleteCheckpoint(String partitionId)
    {
    	// Make this a no-op to avoid deleting leases by accident.
        return null;
    }

    
    //
    // Lease operations.
    //

    @Override
    public int getLeaseRenewIntervalInMilliseconds()
    {
    	return AzureStorageCheckpointLeaseManager.leaseRenewIntervalInMilliseconds;
    }
    
    @Override
    public Future<Boolean> leaseStoreExists()
    {
        return EventProcessorHost.getExecutorService().submit(() -> this.eventHubContainer.exists());
    }

    @Override
    public Future<Boolean> createLeaseStoreIfNotExists()
    {
        return EventProcessorHost.getExecutorService().submit(() -> this.eventHubContainer.createIfNotExists());
    }

    @Override
    public Future<Lease> getLease(String partitionId)
    {
        return EventProcessorHost.getExecutorService().submit(() -> getLeaseSync(partitionId));
    }
    
    private AzureBlobLease getLeaseSync(String partitionId) throws URISyntaxException, IOException, StorageException
    {
    	AzureBlobLease retval = null;
    	
		CloudBlockBlob leaseBlob = this.consumerGroupDirectory.getBlockBlobReference(partitionId);
		if (leaseBlob.exists())
		{
			retval = downloadLease(leaseBlob);
		}

    	return retval;
    }

    @Override
    public Iterable<Future<Lease>> getAllLeases()
    {
        ArrayList<Future<Lease>> leaseFutures = new ArrayList<Future<Lease>>();
        Iterable<String> partitionIds = this.host.getPartitionManager().getPartitionIds(); 
        for (String id : partitionIds)
        {
            leaseFutures.add(getLease(id));
        }
        return leaseFutures;
    }

    @Override
    public Future<Lease> createLeaseIfNotExists(String partitionId)
    {
        return EventProcessorHost.getExecutorService().submit(() -> createLeaseIfNotExistsSync(partitionId));
    }
    
    private AzureBlobLease createLeaseIfNotExistsSync(String partitionId) throws URISyntaxException, IOException, StorageException
    {
    	AzureBlobLease returnLease = null;
    	try
    	{
    		CloudBlockBlob leaseBlob = this.consumerGroupDirectory.getBlockBlobReference(partitionId);
    		returnLease = new AzureBlobLease(this.host.getEventHubPath(), this.host.getConsumerGroupName(), partitionId, leaseBlob);
    		String jsonLease = this.gson.toJson(returnLease);
    		this.host.logWithHostAndPartition(Level.INFO, partitionId,
    				"CreateLeaseIfNotExist - leaseContainerName: " + this.host.getEventHubPath() + " consumerGroupName: " + this.host.getConsumerGroupName());
    		leaseBlob.uploadText(jsonLease, null, AccessCondition.generateIfNoneMatchCondition("*"), null, null);
    	}
    	catch (StorageException se)
    	{
    		StorageExtendedErrorInformation extendedErrorInfo = se.getExtendedErrorInformation();
    		if ((extendedErrorInfo != null) &&
    				((extendedErrorInfo.getErrorCode().compareTo(StorageErrorCodeStrings.BLOB_ALREADY_EXISTS) == 0) ||
    				 (extendedErrorInfo.getErrorCode().compareTo(StorageErrorCodeStrings.LEASE_ID_MISSING) == 0))) // occurs when somebody else already has leased the blob
    		{
    			// The blob already exists.
    			this.host.logWithHostAndPartition(Level.INFO, partitionId, "Lease already exists");
        		returnLease = getLeaseSync(partitionId);
    		}
    		else
    		{
    			System.out.println("errorCode " + extendedErrorInfo.getErrorCode());
    			System.out.println("errorString " + extendedErrorInfo.getErrorMessage());
    			this.host.logWithHostAndPartition(Level.SEVERE, partitionId,
    				"CreateLeaseIfNotExist StorageException - leaseContainerName: " + this.host.getEventHubPath() + " consumerGroupName: " + this.host.getConsumerGroupName(),
    				se);
    			throw se;
    		}
    	}
    	
    	return returnLease;
    }

    @Override
    public Future<Void> deleteLease(Lease lease)
    {
        return EventProcessorHost.getExecutorService().submit(() -> deleteLeaseSync((AzureBlobLease)lease));
    }
    
    private Void deleteLeaseSync(AzureBlobLease lease) throws StorageException
    {
    	this.host.logWithHostAndPartition(Level.INFO, lease.getPartitionId(), "Deleting lease");
    	lease.getBlob().deleteIfExists();
    	return null;
    }

    @Override
    public Future<Boolean> acquireLease(Lease lease)
    {
        return EventProcessorHost.getExecutorService().submit(() -> acquireLeaseSync((AzureBlobLease)lease));
    }
    
    private Boolean acquireLeaseSync(AzureBlobLease lease) throws Exception
    {
    	this.host.logWithHostAndPartition(Level.INFO, lease.getPartitionId(), "Acquiring lease");
    	
    	CloudBlockBlob leaseBlob = lease.getBlob();
    	boolean retval = true;
    	String newLeaseId = UUID.randomUUID().toString();
    	try
    	{
    		String newToken = null;
    		leaseBlob.downloadAttributes();
	    	if (leaseBlob.getProperties().getLeaseState() == LeaseState.LEASED)
	    	{
	    		this.host.logWithHostAndPartition(Level.FINE, lease.getPartitionId(), "changeLease");
	    		newToken = leaseBlob.changeLease(newLeaseId, AccessCondition.generateLeaseCondition(lease.getToken()));
	    	}
	    	else
	    	{
	    		this.host.logWithHostAndPartition(Level.FINE, lease.getPartitionId(), "acquireLease");
	    		newToken = leaseBlob.acquireLease(AzureStorageCheckpointLeaseManager.leaseIntervalInSeconds, newLeaseId);
	    	}
	    	lease.setToken(newToken);
	    	lease.setOwner(this.host.getHostName());
	    	lease.incrementEpoch(); // Increment epoch each time lease is acquired or stolen by a new host
	    	leaseBlob.uploadText(this.gson.toJson(lease), null, AccessCondition.generateLeaseCondition(lease.getToken()), null, null);
    	}
    	catch (StorageException se)
    	{
    		if (wasLeaseLost(se))
    		{
    			retval = false;
    		}
    		else
    		{
    			throw se;
    		}
    	}
    	
    	return retval;
    }

    @Override
    public Future<Boolean> renewLease(Lease lease)
    {
        return EventProcessorHost.getExecutorService().submit(() -> renewLeaseSync((AzureBlobLease)lease));
    }
    
    private Boolean renewLeaseSync(AzureBlobLease lease) throws Exception
    {
    	this.host.logWithHostAndPartition(Level.INFO, lease.getPartitionId(), "Renewing lease");
    	
    	CloudBlockBlob leaseBlob = lease.getBlob();
    	boolean retval = true;
    	
    	try
    	{
    		leaseBlob.renewLease(AccessCondition.generateLeaseCondition(lease.getToken()), this.renewRequestOptions, null);
    	}
    	catch (StorageException se)
    	{
    		if (wasLeaseLost(se))
    		{
    			retval = false;
    		}
    		else
    		{
    			throw se;
    		}
    	}
    	
    	return retval;
    }

    @Override
    public Future<Boolean> releaseLease(Lease lease)
    {
        return EventProcessorHost.getExecutorService().submit(() -> releaseLeaseSync((AzureBlobLease)lease));
    }
    
    private Boolean releaseLeaseSync(AzureBlobLease lease) throws Exception
    {
    	this.host.logWithHostAndPartition(Level.INFO, lease.getPartitionId(), "Releasing lease");
    	
    	CloudBlockBlob leaseBlob = lease.getBlob();
    	boolean retval = true;
    	try
    	{
    		String leaseId = lease.getToken();
    		AzureBlobLease releasedCopy = new AzureBlobLease(lease);
    		releasedCopy.setToken("");
    		releasedCopy.setOwner("");
    		leaseBlob.uploadText(this.gson.toJson(releasedCopy), null, AccessCondition.generateLeaseCondition(leaseId), null, null);
    		leaseBlob.releaseLease(AccessCondition.generateLeaseCondition(leaseId));
    	}
    	catch (StorageException se)
    	{
    		if (wasLeaseLost(se))
    		{
    			retval = false;
    		}
    		else
    		{
    			throw se;
    		}
    	}
    	
    	return retval;
    }

    @Override
    public Future<Boolean> updateLease(Lease lease)
    {
        return EventProcessorHost.getExecutorService().submit(() -> updateLeaseSync((AzureBlobLease)lease));
    }
    
    public Boolean updateLeaseSync(AzureBlobLease lease) throws Exception
    {
    	if (lease == null)
    	{
    		return false;
    	}
    	
    	this.host.logWithHostAndPartition(Level.INFO, lease.getPartitionId(), "Updating lease");
    	
    	String token = lease.getToken();
    	if ((token == null) || (token.length() == 0))
    	{
    		return false;
    	}
    	
    	// First, renew the lease to make sure the update will go through.
    	if (!renewLeaseSync(lease))
    	{
    		return false;
    	}
    	
    	CloudBlockBlob leaseBlob = lease.getBlob();
    	try
    	{
    		String jsonToUpload = this.gson.toJson(lease);
    		this.host.logWithHost(Level.FINE, "Raw JSON uploading: " + jsonToUpload);
    		leaseBlob.uploadText(jsonToUpload, null, AccessCondition.generateLeaseCondition(token), null, null);
    	}
    	catch (StorageException se)
    	{
    		if (wasLeaseLost(se))
    		{
    			throw new LeaseLostException(lease, se);
    		}
    		else
    		{
    			throw se;
    		}
    	}
    	
    	return true;
    }

    private AzureBlobLease downloadLease(CloudBlockBlob blob) throws StorageException, IOException
    {
    	String jsonLease = blob.downloadText();
    	this.host.logWithHost(Level.FINE, "Raw JSON downloaded: " + jsonLease);
    	AzureBlobLease rehydrated = this.gson.fromJson(jsonLease, AzureBlobLease.class);
    	AzureBlobLease blobLease = new AzureBlobLease(rehydrated, blob);
    	return blobLease;
    }
    
    private boolean wasLeaseLost(StorageException se)
    {
    	boolean retval = false;
		this.host.logWithHost(Level.FINE, "WAS LEASE LOST?");
		this.host.logWithHost(Level.FINE, "Http " + se.getHttpStatusCode());
    	if ((se.getHttpStatusCode() == 409) || // conflict
    		(se.getHttpStatusCode() == 412)) // precondition failed
    	{
    		StorageExtendedErrorInformation extendedErrorInfo = se.getExtendedErrorInformation();
    		if (extendedErrorInfo != null)
    		{
    			String errorCode = extendedErrorInfo.getErrorCode();
				this.host.logWithHost(Level.FINE, "Error code: " + errorCode);
				this.host.logWithHost(Level.FINE, "Error message: " + extendedErrorInfo.getErrorMessage());
    			if ((errorCode.compareTo(StorageErrorCodeStrings.LEASE_LOST) == 0) ||
    				(errorCode.compareTo(StorageErrorCodeStrings.LEASE_ID_MISMATCH_WITH_LEASE_OPERATION) == 0) ||
    				(errorCode.compareTo(StorageErrorCodeStrings.LEASE_ID_MISMATCH_WITH_BLOB_OPERATION) == 0))
    			{
    				retval = true;
    			}
    		}
    	}
    	return retval;
    }
}
