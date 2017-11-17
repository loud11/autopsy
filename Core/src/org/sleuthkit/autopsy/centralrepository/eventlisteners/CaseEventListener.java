/*
 * Central Repository
 *
 * Copyright 2015-2017 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.centralrepository.eventlisteners;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.openide.util.NbBundle.Messages;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.casemodule.events.BlackBoardArtifactTagAddedEvent;
import org.sleuthkit.autopsy.casemodule.events.BlackBoardArtifactTagDeletedEvent;
import org.sleuthkit.autopsy.casemodule.events.ContentTagAddedEvent;
import org.sleuthkit.autopsy.casemodule.events.ContentTagDeletedEvent;
import org.sleuthkit.autopsy.casemodule.events.DataSourceAddedEvent;
import org.sleuthkit.autopsy.casemodule.services.TagsManager;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.centralrepository.datamodel.CorrelationAttribute;
import org.sleuthkit.autopsy.centralrepository.datamodel.EamArtifactUtil;
import org.sleuthkit.autopsy.centralrepository.datamodel.CorrelationCase;
import org.sleuthkit.autopsy.centralrepository.datamodel.CorrelationDataSource;
import org.sleuthkit.autopsy.centralrepository.datamodel.EamDb;
import org.sleuthkit.autopsy.centralrepository.datamodel.EamDbException;
import org.sleuthkit.autopsy.centralrepository.datamodel.EamOrganization;
import org.sleuthkit.autopsy.coreutils.ThreadUtils;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.BlackboardArtifact;
import org.sleuthkit.datamodel.BlackboardArtifactTag;
import org.sleuthkit.datamodel.Content;
import org.sleuthkit.datamodel.ContentTag;
import org.sleuthkit.datamodel.TskCoreException;
import org.sleuthkit.datamodel.TskData;
import org.sleuthkit.datamodel.TskDataException;

/**
 * Listen for case events and update entries in the Central Repository database
 * accordingly
 */
@Messages({"caseeventlistener.evidencetag=Evidence"})
final class CaseEventListener implements PropertyChangeListener {

    private static final Logger LOGGER = Logger.getLogger(CaseEventListener.class.getName());
    private final ExecutorService jobProcessingExecutor;
    private static final String CASE_EVENT_THREAD_NAME = "Case-Event-Listener-%d";

    CaseEventListener() {
        jobProcessingExecutor = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat(CASE_EVENT_THREAD_NAME).build());
    }

    void shutdown() {
        ThreadUtils.shutDownTaskExecutor(jobProcessingExecutor);
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        EamDb dbManager;
        try {
            dbManager = EamDb.getInstance();
        } catch (EamDbException ex) {
            LOGGER.log(Level.SEVERE, "Failed to get instance of db manager.", ex);
            return;
        }
        switch (Case.Events.valueOf(evt.getPropertyName())) {
            case CONTENT_TAG_ADDED:
            case CONTENT_TAG_DELETED: {
                jobProcessingExecutor.submit(new ContentTagTask(dbManager, evt));
            }
            break;

            case BLACKBOARD_ARTIFACT_TAG_DELETED:
            case BLACKBOARD_ARTIFACT_TAG_ADDED: {
                jobProcessingExecutor.submit(new BlackboardTagTask(dbManager, evt));
            }
            break;

            case DATA_SOURCE_ADDED: {
                jobProcessingExecutor.submit(new DataSourceAddedTask(dbManager, evt));
            }
            break;

            case CURRENT_CASE: {
                jobProcessingExecutor.submit(new CurrentCaseTask(dbManager, evt));
            }
            break;
        }
    }

    private final class ContentTagTask implements Runnable {

        private final EamDb dbManager;
        private final PropertyChangeEvent event;

        private ContentTagTask(EamDb db, PropertyChangeEvent evt) {
            dbManager = db;
            event = evt;
        }

        @Override
        public void run() {
            if (!EamDb.isEnabled()) {
                return;
            }

            AbstractFile af;
            TskData.FileKnown knownStatus;
            String comment;
            if (Case.Events.valueOf(event.getPropertyName()) == Case.Events.CONTENT_TAG_ADDED) {
                // For added tags, we want to change the known status to BAD if the 
                // tag that was just added is in the list of central repo tags.
                final ContentTagAddedEvent tagAddedEvent = (ContentTagAddedEvent) event;
                final ContentTag tagAdded = tagAddedEvent.getAddedTag();

                if (TagsManager.getNotableTagDisplayNames().contains(tagAdded.getName().getDisplayName())) {
                    if (tagAdded.getContent() instanceof AbstractFile) {
                        af = (AbstractFile) tagAdded.getContent();
                        knownStatus = TskData.FileKnown.BAD;
                        comment = tagAdded.getComment();
                    } else {
                        LOGGER.log(Level.WARNING, "Error updating non-file object");
                        return;
                    }
                } else {
                    // The added tag isn't flagged as bad in central repo, so do nothing
                    return;
                }
            } else { // CONTENT_TAG_DELETED
                // For deleted tags, we want to set the file status to UNKNOWN if:
                //   - The tag that was just removed is notable in central repo
                //   - There are no remaining tags that are notable 
                final ContentTagDeletedEvent tagDeletedEvent = (ContentTagDeletedEvent) event;
                long contentID = tagDeletedEvent.getDeletedTagInfo().getContentID();

                String tagName = tagDeletedEvent.getDeletedTagInfo().getName().getDisplayName();
                if (!TagsManager.getNotableTagDisplayNames().contains(tagName)) {
                    // If the tag that got removed isn't on the list of central repo tags, do nothing
                    return;
                }

                try {
                    // Get the remaining tags on the content object
                    Content content = Case.getCurrentCase().getSleuthkitCase().getContentById(contentID);
                    TagsManager tagsManager = Case.getCurrentCase().getServices().getTagsManager();
                    List<ContentTag> tags = tagsManager.getContentTagsByContent(content);

                    if (tags.stream()
                            .map(tag -> tag.getName().getDisplayName())
                            .filter(TagsManager.getNotableTagDisplayNames()::contains)
                            .collect(Collectors.toList())
                            .isEmpty()) {

                        // There are no more bad tags on the object
                        if (content instanceof AbstractFile) {
                            af = (AbstractFile) content;
                            knownStatus = TskData.FileKnown.UNKNOWN;
                            comment = "";
                        } else {
                            LOGGER.log(Level.WARNING, "Error updating non-file object");
                            return;
                        }
                    } else {
                        // There's still at least one bad tag, so leave the known status as is
                        return;
                    }
                } catch (TskCoreException ex) {
                    LOGGER.log(Level.SEVERE, "Failed to find content", ex);
                    return;
                }
            }

            final CorrelationAttribute eamArtifact = EamArtifactUtil.getEamArtifactFromContent(af,
                    knownStatus, comment);

            if (eamArtifact != null) {
                // send update to Central Repository db
                try {
                    dbManager.setArtifactInstanceKnownStatus(eamArtifact, knownStatus);
                } catch (EamDbException ex) {
                    LOGGER.log(Level.SEVERE, "Error connecting to Central Repository database while setting artifact known status.", ex); //NON-NLS
                }
            }
        } // CONTENT_TAG_ADDED, CONTENT_TAG_DELETED
    }

    private final class BlackboardTagTask implements Runnable {

        private final EamDb dbManager;
        private final PropertyChangeEvent event;

        private BlackboardTagTask(EamDb db, PropertyChangeEvent evt) {
            dbManager = db;
            event = evt;
        }

        @Override
        public void run() {
            if (!EamDb.isEnabled()) {
                return;
            }

            Content content;
            BlackboardArtifact bbArtifact;
            TskData.FileKnown knownStatus;
            String comment;
            if (Case.Events.valueOf(event.getPropertyName()) == Case.Events.BLACKBOARD_ARTIFACT_TAG_ADDED) {
                // For added tags, we want to change the known status to BAD if the 
                // tag that was just added is in the list of central repo tags.
                final BlackBoardArtifactTagAddedEvent tagAddedEvent = (BlackBoardArtifactTagAddedEvent) event;
                final BlackboardArtifactTag tagAdded = tagAddedEvent.getAddedTag();

                if (TagsManager.getNotableTagDisplayNames().contains(tagAdded.getName().getDisplayName())) {
                    content = tagAdded.getContent();
                    bbArtifact = tagAdded.getArtifact();
                    knownStatus = TskData.FileKnown.BAD;
                    comment = tagAdded.getComment();
                } else {
                    // The added tag isn't flagged as bad in central repo, so do nothing
                    return;
                }
            } else { //BLACKBOARD_ARTIFACT_TAG_DELETED
                // For deleted tags, we want to set the file status to UNKNOWN if:
                //   - The tag that was just removed is notable in central repo
                //   - There are no remaining tags that are notable 
                final BlackBoardArtifactTagDeletedEvent tagDeletedEvent = (BlackBoardArtifactTagDeletedEvent) event;
                long contentID = tagDeletedEvent.getDeletedTagInfo().getContentID();
                long artifactID = tagDeletedEvent.getDeletedTagInfo().getArtifactID();

                String tagName = tagDeletedEvent.getDeletedTagInfo().getName().getDisplayName();
                if (!TagsManager.getNotableTagDisplayNames().contains(tagName)) {
                    // If the tag that got removed isn't on the list of central repo tags, do nothing
                    return;
                }

                try {
                    // Get the remaining tags on the artifact
                    content = Case.getCurrentCase().getSleuthkitCase().getContentById(contentID);
                    bbArtifact = Case.getCurrentCase().getSleuthkitCase().getBlackboardArtifact(artifactID);
                    TagsManager tagsManager = Case.getCurrentCase().getServices().getTagsManager();
                    List<BlackboardArtifactTag> tags = tagsManager.getBlackboardArtifactTagsByArtifact(bbArtifact);

                    if (tags.stream()
                            .map(tag -> tag.getName().getDisplayName())
                            .filter(TagsManager.getNotableTagDisplayNames()::contains)
                            .collect(Collectors.toList())
                            .isEmpty()) {

                        // There are no more bad tags on the object
                        knownStatus = TskData.FileKnown.UNKNOWN;
                        comment = "";

                    } else {
                        // There's still at least one bad tag, so leave the known status as is
                        return;
                    }
                } catch (TskCoreException ex) {
                    LOGGER.log(Level.SEVERE, "Failed to find content", ex);
                    return;
                }
            }

            if ((content instanceof AbstractFile) && (((AbstractFile) content).getKnown() == TskData.FileKnown.KNOWN)) {
                return;
            }

            List<CorrelationAttribute> convertedArtifacts = EamArtifactUtil.getCorrelationAttributeFromBlackboardArtifact(bbArtifact, true, true);
            for (CorrelationAttribute eamArtifact : convertedArtifacts) {
                eamArtifact.getInstances().get(0).setComment(comment);
                try {
                    dbManager.setArtifactInstanceKnownStatus(eamArtifact, knownStatus);
                } catch (EamDbException ex) {
                    LOGGER.log(Level.SEVERE, "Error connecting to Central Repository database while setting artifact known status.", ex); //NON-NLS
                }
            }
        } // BLACKBOARD_ARTIFACT_TAG_ADDED, BLACKBOARD_ARTIFACT_TAG_DELETED

    }

    private final class DataSourceAddedTask implements Runnable {

        private final EamDb dbManager;
        private final PropertyChangeEvent event;

        private DataSourceAddedTask(EamDb db, PropertyChangeEvent evt) {
            dbManager = db;
            event = evt;
        }

        @Override
        public void run() {
            if (!EamDb.isEnabled()) {
                return;
            }

            final DataSourceAddedEvent dataSourceAddedEvent = (DataSourceAddedEvent) event;
            Content newDataSource = dataSourceAddedEvent.getDataSource();

            try {
                String deviceId = Case.getCurrentCase().getSleuthkitCase().getDataSource(newDataSource.getId()).getDeviceId();
                CorrelationCase correlationCase = dbManager.getCaseByUUID(Case.getCurrentCase().getName());
                if (null == correlationCase) {
                    dbManager.newCase(Case.getCurrentCase());
                    correlationCase = dbManager.getCaseByUUID(Case.getCurrentCase().getName());
                }
                if (null == dbManager.getDataSourceDetails(correlationCase, deviceId)) {
                    dbManager.newDataSource(CorrelationDataSource.fromTSKDataSource(correlationCase, newDataSource));
                }
            } catch (EamDbException ex) {
                LOGGER.log(Level.SEVERE, "Error connecting to Central Repository database.", ex); //NON-NLS
            } catch (TskCoreException | TskDataException ex) {
                LOGGER.log(Level.SEVERE, "Error getting data source from DATA_SOURCE_ADDED event content.", ex); //NON-NLS
            }
        } // DATA_SOURCE_ADDED
    }

    private final class CurrentCaseTask implements Runnable {

        private final EamDb dbManager;
        private final PropertyChangeEvent event;

        private CurrentCaseTask(EamDb db, PropertyChangeEvent evt) {
            dbManager = db;
            event = evt;
        }

        @Override
        public void run() {
            /*
             * A case has been opened if evt.getOldValue() is null and
             * evt.getNewValue() is a valid Case.
             */
            if ((null == event.getOldValue()) && (event.getNewValue() instanceof Case)) {
                Case curCase = (Case) event.getNewValue();
                IngestEventsListener.resetCeModuleInstanceCount();
                
                CorrelationCase curCeCase = new CorrelationCase(
                        -1,
                        curCase.getName(), // unique case ID
                        EamOrganization.getDefault(),
                        curCase.getDisplayName(),
                        curCase.getCreatedDate(),
                        curCase.getNumber(),
                        curCase.getExaminer(),
                        curCase.getExaminerEmail(),
                        curCase.getExaminerPhone(),
                        curCase.getCaseNotes());

                if (!EamDb.isEnabled()) {
                    return;
                }

                try {
                    // NOTE: Cannot determine if the opened case is a new case or a reopened case,
                    //  so check for existing name in DB and insert if missing.
                    CorrelationCase existingCase = dbManager.getCaseByUUID(curCeCase.getCaseUUID());

                    if (null == existingCase) {
                        dbManager.newCase(curCeCase);
                    }
                } catch (EamDbException ex) {
                    LOGGER.log(Level.SEVERE, "Error connecting to Central Repository database.", ex); //NON-NLS
                }
            }
        } // CURRENT_CASE
    }
}