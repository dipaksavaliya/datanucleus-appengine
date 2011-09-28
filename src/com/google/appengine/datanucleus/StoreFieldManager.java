/**********************************************************************
Copyright (c) 2011 Google Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
**********************************************************************/
package com.google.appengine.datanucleus;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.exceptions.NucleusDataStoreException;
import org.datanucleus.exceptions.NucleusFatalUserException;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.ArrayMetaData;
import org.datanucleus.metadata.CollectionMetaData;
import org.datanucleus.metadata.ColumnMetaData;
import org.datanucleus.metadata.NullValue;
import org.datanucleus.metadata.Relation;
import org.datanucleus.store.ExecutionContext;
import org.datanucleus.store.ObjectProvider;
import org.datanucleus.store.exceptions.NotYetFlushedException;
import org.datanucleus.store.mapped.mapping.EmbeddedPCMapping;
import org.datanucleus.store.mapped.mapping.InterfaceMapping;
import org.datanucleus.store.mapped.mapping.JavaTypeMapping;
import org.datanucleus.store.mapped.mapping.MappingCallbacks;
import org.datanucleus.store.mapped.mapping.PersistableMapping;
import org.datanucleus.store.mapped.mapping.SerialisedPCMapping;
import org.datanucleus.store.mapped.mapping.SerialisedReferenceMapping;
import org.datanucleus.store.types.TypeManager;
import org.datanucleus.store.types.sco.SCO;
import org.datanucleus.util.Localiser;

import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.datastore.Text;
import com.google.appengine.datanucleus.mapping.DatastoreTable;
import com.google.appengine.datanucleus.mapping.InsertMappingConsumer;

/**
 * FieldManager to handle the putting of fields from a managed object into an Entity.
 * There are typically two steps to use of this field manager.
 * <ul>
 * <li>Call <pre>op.provideFields(fieldNumbers, fieldMgr);</pre></li>
 * <li>Call <pre>fieldMgr.storeRelations(keyRegistry);</pre>
 * </ul>
 * The first step will process the requested fields and set them in the Entity as required, and will also
 * register any relation fields for later processing. The second step will take the relation fields (if any)
 * from step 1, perform cascade-persist on them, and set child keys in the (parent) Entity where required.
 */
public class StoreFieldManager extends DatastoreFieldManager {
  protected static final Localiser GAE_LOCALISER = Localiser.getInstance(
      "com.google.appengine.datanucleus.Localisation", DatastoreManager.class.getClassLoader());

  private static final String PARENT_ALREADY_SET =
    "Cannot set both the primary key and a parent pk field.  If you want the datastore to "
    + "generate an id for you, set the parent pk field to be the value of your parent key "
    + "and leave the primary key field blank.  If you wish to "
    + "provide a named key, leave the parent pk field blank and set the primary key to be a "
    + "Key object made up of both the parent key and the named child.";

  public static final int IS_FK_VALUE = -2;
  private static final int[] IS_FK_VALUE_ARR = {IS_FK_VALUE};

  protected enum Operation { INSERT, UPDATE };

  protected final Operation operation;

  protected boolean parentAlreadySet = false;

  protected boolean keyAlreadySet = false;

  private final List<RelationStoreInformation> relationStoreInfos = Utils.newArrayList();

  /**
   * Constructor for a StoreFieldManager when inserting a new object.
   * The Entity will be constructed.
   * @param op ObjectProvider of the object being stored
   * @param kind Kind of entity
   */
  public StoreFieldManager(ObjectProvider op, String kind) {
    super(op, new Entity(kind), null);
    this.operation = Operation.INSERT;
  }

  /**
   * Constructor for a StoreFieldManager when updating an object.
   * The Entity will be passed in (to be updated).
   * @param op ObjectProvider of the object being stored
   * @param datastoreEntity The Entity to update with the field values
   * @param fieldNumbers The field numbers being updated in the Entity
   */
  public StoreFieldManager(ObjectProvider op, Entity datastoreEntity, int[] fieldNumbers) {
    super(op, datastoreEntity, fieldNumbers);
    this.operation = Operation.UPDATE;
  }

  public void storeBooleanField(int fieldNumber, boolean value) {
    storeFieldInEntity(fieldNumber, value);
  }

  public void storeByteField(int fieldNumber, byte value) {
    storeFieldInEntity(fieldNumber, value);
  }

  public void storeCharField(int fieldNumber, char value) {
    storeFieldInEntity(fieldNumber, value);
  }

  public void storeDoubleField(int fieldNumber, double value) {
    storeFieldInEntity(fieldNumber, value);
  }

  public void storeFloatField(int fieldNumber, float value) {
    storeFieldInEntity(fieldNumber, value);
  }

  public void storeIntField(int fieldNumber, int value) {
    storeFieldInEntity(fieldNumber, value);
  }

  public void storeLongField(int fieldNumber, long value) {
    storeFieldInEntity(fieldNumber, value);
  }

  public void storeShortField(int fieldNumber, short value) {
    storeFieldInEntity(fieldNumber, value);
  }

  public void storeStringField(int fieldNumber, String value) {
    if (isPK(fieldNumber)) {
      storeStringPKField(fieldNumber, value);
    } else if (MetaDataUtils.isParentPKField(getClassMetaData(), fieldNumber)) {
      storeParentStringField(value);
    } else if (MetaDataUtils.isPKNameField(getClassMetaData(), fieldNumber)) {
      storePKNameField(fieldNumber, value);
    } else {
      // could be a JPA "lob" field, in which case we want to store it as Text.
      // DataNucleus sets a cmd with a jdbc type of CLOB if this is the case.
      Object valueToStore = value;
      AbstractMemberMetaData ammd = getMetaData(fieldNumber);
      if (ammd.getColumnMetaData() != null &&
          ammd.getColumnMetaData().length == 1) {
        if ("CLOB".equals(ammd.getColumnMetaData()[0].getJdbcType())) {
          valueToStore = new Text(value);
        }/* else if (ammd.getColumnMetaData()[0].getLength() > 500) {
          // Can only store up to 500 characters in String, so use Text
          valueToStore = new Text(value);
        }*/
      }

      storeFieldInEntity(fieldNumber, valueToStore);
    }
  }

  public void storeObjectField(int fieldNumber, Object value) {
    if (isPK(fieldNumber)) {
      storePrimaryKey(fieldNumber, value);
    } else if (MetaDataUtils.isParentPKField(getClassMetaData(), fieldNumber)) {
      storeParentField(fieldNumber, value);
    } else if (MetaDataUtils.isPKIdField(getClassMetaData(), fieldNumber)) {
      storePKIdField(fieldNumber, value);
    } else {
      ClassLoaderResolver clr = getClassLoaderResolver();
      AbstractMemberMetaData ammd = getMetaData(fieldNumber);
      int relationType = ammd.getRelationType(clr);

      storeFieldInEntity(fieldNumber, value);

      if (!(value instanceof SCO)) {
        // TODO Wrap SCO fields, remove the relation check so it applies to all. See Issue 144
        // This is currently not done since the elements may not be persisted at this point and the test classes
        // rely on "id" being set for hashCode/equals to work. Fix the persistence process first
        if (relationType == Relation.NONE) {
          getObjectProvider().wrapSCOField(fieldNumber, value, false, false, true);
        }
      }
    }
  }

  /**
   * Method to store the provided value in the Entity for the specified field.
   * @param fieldNumber The absolute field number
   * @param value Value to store (or rather to manipulate into a suitable form for the datastore).
   */
  private void storeFieldInEntity(int fieldNumber, Object value) {
    AbstractMemberMetaData ammd = getMetaData(fieldNumber);
    if (!(operation == Operation.INSERT && ammd.isInsertable()) &&
        !(operation == Operation.UPDATE && ammd.isUpdateable())) {
      return;
    }

    if (ammd.getEmbeddedMetaData() != null) {
      // Embedded field handling
      ObjectProvider embeddedOP = getEmbeddedObjectProvider(ammd, fieldNumber, value);
      // TODO Create own FieldManager instead of reusing this one
      // We need to build a mapping consumer for the embedded class so that we get correct
      // fieldIndex --> metadata mappings for the class in the proper embedded context
      // TODO(maxr) Consider caching this
      InsertMappingConsumer mc = buildMappingConsumer(
          embeddedOP.getClassMetaData(), getClassLoaderResolver(),
          embeddedOP.getClassMetaData().getAllMemberPositions(),
          ammd.getEmbeddedMetaData());
      fieldManagerStateStack.addFirst(
          new FieldManagerState(embeddedOP, getEmbeddedAbstractMemberMetaDataProvider(mc), mc, true));
      embeddedOP.provideFields(embeddedOP.getClassMetaData().getAllMemberPositions(), this);
      fieldManagerStateStack.removeFirst();
      return;
    }

    ExecutionContext ec = getExecutionContext();
    ClassLoaderResolver clr = getClassLoaderResolver();
    if (ammd.isSerialized()) {
      if (value != null) {
        // Serialize the field (producing a Blob)
        value = getStoreManager().getSerializationManager().serialize(clr, ammd, value);
      } else {
        // Make sure we can have a null property for this field
        checkSettingToNullValue(ammd, value);
      }
      EntityUtils.setEntityProperty(datastoreEntity, ammd, 
          EntityUtils.getPropertyName(getStoreManager().getIdentifierFactory(), ammd), value);
      return;
    }

    if (value != null ) {
      // Perform any conversions from the field type to the stored-type
      TypeManager typeMgr = op.getExecutionContext().getNucleusContext().getTypeManager();
      value = getConversionUtils().pojoValueToDatastoreValue(typeMgr, clr, value, ammd);
    }

    int relationType = ammd.getRelationType(clr);
    if (relationType == Relation.NONE) {
      // Basic field
      if (value == null) {
        checkSettingToNullValue(ammd, value);
      }
      if (value instanceof SCO) {
        // Use the unwrapped value so the datastore doesn't fail on unknown types
        value = ((SCO)value).getValue();
      }
      EntityUtils.setEntityProperty(datastoreEntity, ammd, 
          EntityUtils.getPropertyName(getStoreManager().getIdentifierFactory(), ammd), value);
      return;
    }

    // Register this relation field for later update
    relationStoreInfos.add(new RelationStoreInformation(ammd, value));

    boolean owned = MetaDataUtils.isOwnedRelation(ammd);
    if (owned) {
      // Owned - Skip out for all situations where aren't the owner (since our key has the parent key)
      if (!getStoreManager().storageVersionAtLeast(StorageVersion.WRITE_OWNED_CHILD_KEYS_TO_PARENTS)) {
        // don't write child keys to the parent if the storage version isn't high enough
        return;
      }
      if (relationType == Relation.MANY_TO_ONE_BI) {
        // We don't store any "FK" of the parent TODO We ought to but Google don't want to
        return;
      } else if (relationType == Relation.ONE_TO_ONE_BI && ammd.getMappedBy() != null) {
        // We don't store any "FK" of the other side TODO We ought to but Google don't want to
        return;
      }
    }

    if (operation == Operation.INSERT) {
      if (value == null) {
        // Nothing to extract
        checkSettingToNullValue(ammd, value);
      } else if (Relation.isRelationSingleValued(relationType)) {
        Key key = EntityUtils.extractChildKey(value, ec, datastoreEntity);
        if (key != null && owned && !datastoreEntity.getKey().equals(key.getParent())) {
          // Detect attempt to add an object with its key set (and hence parent set) on owned field
          throw new NucleusFatalUserException(GAE_LOCALISER.msg("AppEngine.OwnedChildCannotChangeParent",
              key, datastoreEntity.getKey()));
        }
        value = key;
      } else if (Relation.isRelationMultiValued(relationType)) {
        if (ammd.hasCollection()) {
          Collection coll = (Collection) value;
          List<Key> keys = Utils.newArrayList();
          for (Object obj : coll) {
            Key key = EntityUtils.extractChildKey(obj, ec, datastoreEntity);
            if (key != null) {
              keys.add(key);
              if (owned && !datastoreEntity.getKey().equals(key.getParent())) {
                // Detect attempt to add an object with its key set (and hence parent set) on owned field
                throw new NucleusFatalUserException(GAE_LOCALISER.msg("AppEngine.OwnedChildCannotChangeParent",
                    key, datastoreEntity.getKey()));
              }
            }
          }
          value = keys;
        }

        if (value instanceof SCO) {
          // Use the unwrapped value so the datastore doesn't fail on unknown types
          value = ((SCO)value).getValue();
        }
      }

      EntityUtils.setEntityProperty(datastoreEntity, ammd, 
          EntityUtils.getPropertyName(getStoreManager().getIdentifierFactory(), ammd), value);
    }
  }

  /**
   * Convenience method to process a related persistable object, persisting and flushing it as necessary
   * and returning the persistent object.
   * @param mmd Field where the object is stored.
   * @param pc The object to process
   * @return The persisted form of the object (or null if deleted)
   */
  Object processPersistable(AbstractMemberMetaData mmd, Object pc) {
    ExecutionContext ec = getExecutionContext();
    if (ec.getApiAdapter().isDeleted(pc)) {
      // Child is deleted, so return null
      return null;
    }

    Object childPC = pc;
    if (ec.getApiAdapter().isDetached(pc)) {
      // Child is detached, so attach it
      childPC = ec.persistObjectInternal(pc, null, -1, ObjectProvider.PC);
    }
    ObjectProvider childOP = ec.findObjectProvider(childPC);
    if (childOP == null) {
      // Not yet persistent, so persist it
      childPC = ec.persistObjectInternal(childPC, null, -1, ObjectProvider.PC);
    }

    // TODO Cater for datastore identity
    Object primaryKey = ec.getApiAdapter().getTargetKeyForSingleFieldIdentity(childOP.getInternalObjectId());
    if (primaryKey == null) {
      // Object has not yet flushed to the datastore
      childOP.flush();
    }
    return childPC;
  }

  void storeParentField(int fieldNumber, Object value) {
    AbstractMemberMetaData mmd = getMetaData(fieldNumber);
    if (mmd.getType().equals(Key.class)) {
      storeParentKeyPK((Key) value);
    } else {
      throw exceptionForUnexpectedKeyType("Parent primary key", fieldNumber);
    }
  }

  private void storePrimaryKey(int fieldNumber, Object value) {
    AbstractMemberMetaData mmd = getMetaData(fieldNumber);
    if (mmd.getType().equals(Long.class)) {
      Key key = null;
      if (value != null) {
        key = KeyFactory.createKey(datastoreEntity.getKind(), (Long) value);
      }
      storeKeyPK(key);
    } else if (mmd.getType().equals(Key.class)) {
      Key key = (Key) value;
      if (key != null && key.getParent() != null && parentAlreadySet) {
        throw new NucleusFatalUserException(PARENT_ALREADY_SET);
      }
      storeKeyPK((Key) value);
    } else {
      throw exceptionForUnexpectedKeyType("Primary key", fieldNumber);
    }
  }

  void storePKIdField(int fieldNumber, Object value) {
    AbstractMemberMetaData mmd = getMetaData(fieldNumber);
    if (!mmd.getType().equals(Long.class)) {
      throw new NucleusFatalUserException(
          "Field with \"" + DatastoreManager.PK_ID + "\" extension must be of type Long");
    }
    Key key = null;
    if (value != null) {
      key = KeyFactory.createKey(datastoreEntity.getKind(), (Long) value);
    }
    storeKeyPK(key);
  }

  private void storePKNameField(int fieldNumber, String value) {
    // TODO(maxr) make sure the pk is an encoded string
    AbstractMemberMetaData mmd = getMetaData(fieldNumber);
    if (!mmd.getType().equals(String.class)) {
      throw new NucleusFatalUserException(
          "Field with \"" + DatastoreManager.PK_ID + "\" extension must be of type String");
    }
    Key key = null;
    if (value != null) {
      key = KeyFactory.createKey(datastoreEntity.getParent(), datastoreEntity.getKind(), value);
    }
    storeKeyPK(key);
  }

  private void storeParentStringField(String value) {
    Key key = null;
    if (value != null) {
      try {
        key = KeyFactory.stringToKey(value);
      } catch (IllegalArgumentException iae) {
        throw new NucleusFatalUserException(
            "Attempt was made to set parent to " + value
            + " but this cannot be converted into a Key.");
      }
    }
    storeParentKeyPK(key);
  }

  private void storeParentKeyPK(Key key) {
    if (key != null && parentAlreadySet) {
      throw new NucleusFatalUserException(PARENT_ALREADY_SET);
    }
    if (datastoreEntity.getParent() != null) {
      // update is ok if it's a no-op
      if (!datastoreEntity.getParent().equals(key)) {
        if (!parentAlreadySet) {
          throw new NucleusFatalUserException(
              "Attempt was made to modify the parent of an object of type "
              + getObjectProvider().getClassMetaData().getFullClassName() + " identified by "
              + "key " + datastoreEntity.getKey() + ".  Parents are immutable (changed value is " + key + ").");
        }
      }
    } else if (key != null) {
      if (operation == StoreFieldManager.Operation.UPDATE) {
        // Shouldn't even happen.
        throw new NucleusFatalUserException("You can only rely on this class to properly handle "
            + "parent pks if you instantiated the class without providing a datastore "
            + "entity to the constructor.");
      }

      if (keyAlreadySet) {
        throw new NucleusFatalUserException(PARENT_ALREADY_SET);
      }

      // If this field is labeled as a parent PK we need to recreate the Entity, passing
      // the value of this field as an arg to the Entity constructor and then moving all
      // properties on the old entity to the new entity.
      datastoreEntity = EntityUtils.recreateEntityWithParent(key, datastoreEntity);
      parentAlreadySet = true;
    } else {
      // Null parent.  Parent is defined on a per-instance basis so
      // annotating a field as a parent is not necessarily a commitment
      // to always having a parent.  Null parent is fine.
    }
  }

  private void storeStringPKField(int fieldNumber, String value) {
    Key key = null;
    if (MetaDataUtils.isEncodedPKField(getClassMetaData(), fieldNumber)) {
      if (value != null) {
        try {
          key = KeyFactory.stringToKey(value);
        } catch (IllegalArgumentException iae) {
          throw new NucleusFatalUserException(
              "Invalid primary key for " + getClassMetaData().getFullClassName() + ".  The "
              + "primary key field is an encoded String but an unencoded value has been provided. "
              + "If you want to set an unencoded value on this field you can either change its "
              + "type to be an unencoded String (remove the \"" + DatastoreManager.ENCODED_PK
              + "\" extension), change its type to be a " + Key.class.getName() + " and then set "
              + "the Key's name field, or create a separate String field for the name component "
              + "of your primary key and add the \"" + DatastoreManager.PK_NAME
              + "\" extension.");
        }
      }
    } else {
      if (value == null) {
        throw new NucleusFatalUserException(
            "Invalid primary key for " + getClassMetaData().getFullClassName() + ".  Cannot have "
            + "a null primary key field if the field is unencoded and of type String.  "
            + "Please provide a value or, if you want the datastore to generate an id on your "
            + "behalf, change the type of the field to Long.");
      }
      if (value != null) {
        if (datastoreEntity.getParent() != null) {
          key = new Entity(datastoreEntity.getKey().getKind(), value, datastoreEntity.getParent()).getKey();
        } else {
          key = new Entity(datastoreEntity.getKey().getKind(), value).getKey();
        }
      }
    }
    storeKeyPK(key);
  }

  private void storeKeyPK(Key key) {
    if (key != null && !datastoreEntity.getKind().equals(key.getKind())) {
      throw new NucleusFatalUserException(
          "Attempt was made to set the primray key of an entity with kind "
          + datastoreEntity.getKind() + " to a key with kind " + key.getKind());
    }
    if (datastoreEntity.getKey().isComplete()) {
      // this modification is only okay if it's actually a no-op
      if (!datastoreEntity.getKey().equals(key)) {
        if (!keyAlreadySet) {
          // Different key provided so the update isn't allowed.
          throw new NucleusFatalUserException(
              "Attempt was made to modify the primary key of an object of type "
              + getObjectProvider().getClassMetaData().getFullClassName() + " identified by "
              + "key " + datastoreEntity.getKey() + "  Primary keys are immutable.  "
              + "(New value: " + key);
        }
      }
    } else if (key != null) {
      Entity old = datastoreEntity;
      if (key.getParent() != null) {
        if (keyAlreadySet) {
          // can't provide a key and a parent - one or the other
          throw new NucleusFatalUserException(PARENT_ALREADY_SET);
        }
        parentAlreadySet = true;
      }
      datastoreEntity = new Entity(key);
      EntityUtils.copyProperties(old, datastoreEntity);
      keyAlreadySet = true;
    }
  }

  private void checkSettingToNullValue(AbstractMemberMetaData ammd, Object value) {
    if (value == null) {
      if (ammd.getNullValue() == NullValue.EXCEPTION) {
        // JDO spec 18.15, throw XXXUserException when trying to store null and have handler set to EXCEPTION
        throw new NucleusUserException("Field/Property " + ammd.getFullFieldName() +
          " is null, but is mandatory as it's described in the jdo metadata");
      }

      ColumnMetaData[] colmds = ammd.getColumnMetaData();
      if (colmds != null && colmds.length > 0) {
        if (colmds[0].getAllowsNull() == Boolean.FALSE) {
          // Column specifically marked as not-nullable
          throw new NucleusDataStoreException("Field/Property " + ammd.getFullFieldName() +
            " is null, but the column is specified as not-nullable");
        }
      }
    }
  }

  /**
   * Method to process all relations that have been identified by earlier call(s) of op.provideField(...).
   * Registers the parent key against any owned child objects, performs cascade-persist, and then stores
   * child keys in the parent where the storageVersion requires it.
   * @param keyRegistry The key registry to set any parent information in
   * @return {@code true} if the entity has had properties updated during this method, {@code false} otherwise.
   */
  boolean storeRelations(KeyRegistry keyRegistry) {
    if (relationStoreInfos.isEmpty()) {
      // No relations waiting to be persisted
      return false;
    }

    ObjectProvider op = getObjectProvider();
    ExecutionContext ec = op.getExecutionContext();
    DatastoreTable table = getDatastoreTable();
    if (datastoreEntity.getKey() != null) {
      Key key = datastoreEntity.getKey();
      AbstractClassMetaData acmd = op.getClassMetaData();
      int[] relationFieldNums = acmd.getRelationMemberPositions(ec.getClassLoaderResolver(), ec.getMetaDataManager());
      if (relationFieldNums != null) {
        for (int i=0;i<relationFieldNums.length;i++) {
          AbstractMemberMetaData mmd = acmd.getMetaDataForManagedMemberAtAbsolutePosition(relationFieldNums[i]);
          boolean owned = MetaDataUtils.isOwnedRelation(mmd);
          if (owned) {
            // Register parent key for all owned related objects
            Object childValue = op.provideField(mmd.getAbsoluteFieldNumber());
            if (childValue != null) {
              if (childValue instanceof Object[]) {
                childValue = Arrays.asList((Object[]) childValue);
              }

              String expectedType = getExpectedChildType(mmd);
              if (childValue instanceof Iterable) {
                // TODO(maxr): Make sure we're not pulling back unnecessary data when we iterate over the values.
                for (Object element : (Iterable) childValue) {
                  addToParentKeyMap(keyRegistry, element, key, op.getExecutionContext(), expectedType, true);
                }
              } else {
                addToParentKeyMap(keyRegistry, childValue, key, op.getExecutionContext(), expectedType, 
                    !table.isParentKeyProvider(mmd));
              }
            }
          } else {
            // Register that related object(s) is unowned
            Object childValue = op.provideField(mmd.getAbsoluteFieldNumber());
            if (childValue != null) {
              if (childValue instanceof Object[]) {
                childValue = Arrays.asList((Object[]) childValue);
              }

              if (childValue instanceof Iterable) {
                // TODO(maxr): Make sure we're not pulling back unnecessary data when we iterate over the values.
                for (Object element : (Iterable) childValue) {
                  keyRegistry.registerUnownedObject(element);
                }
              } else {
                keyRegistry.registerUnownedObject(childValue);
              }
            }
          }
        }
      }
    }

    boolean modifiedEntity = false;

    // Stage 1 : process FKs
    for (RelationStoreInformation relInfo : relationStoreInfos) {
      AbstractMemberMetaData mmd = relInfo.mmd;
      try {
        JavaTypeMapping mapping = table.getMemberMappingInDatastoreClass(relInfo.mmd);
        if (mapping instanceof EmbeddedPCMapping ||
            mapping instanceof SerialisedPCMapping ||
            mapping instanceof SerialisedReferenceMapping ||
            mapping instanceof PersistableMapping ||
            mapping instanceof InterfaceMapping) {
          if (!table.isParentKeyProvider(mmd)) {
            EntityUtils.checkParentage(relInfo.value, op);
            mapping.setObject(getExecutionContext(), datastoreEntity, IS_FK_VALUE_ARR, relInfo.value, op, mmd.getAbsoluteFieldNumber());
          }
        }
      } catch (NotYetFlushedException e) {
        // Ignore this. We always have the object in the datastore, at least partially to get the key
      }
    }

    // Stage 2 : postInsert/postUpdate
    for (RelationStoreInformation relInfo : relationStoreInfos) {
      try {
        JavaTypeMapping mapping = table.getMemberMappingInDatastoreClass(relInfo.mmd);
        if (mapping instanceof MappingCallbacks) {
          if (operation == StoreFieldManager.Operation.INSERT) {
            ((MappingCallbacks)mapping).postInsert(op);
          } else {
            ((MappingCallbacks)mapping).postUpdate(op);
          }
        }
      } catch (NotYetFlushedException e) {
        // Ignore this. We always have the object in the datastore, at least partially to get the key
      }
    }

    // Stage 3 : set child keys in parent
    for (RelationStoreInformation relInfo : relationStoreInfos) {
      AbstractMemberMetaData mmd = relInfo.mmd;
      int relationType = mmd.getRelationType(ec.getClassLoaderResolver());

      boolean owned = MetaDataUtils.isOwnedRelation(mmd);
      if (owned) {
        // Owned relations only store child keys if storageVersion high enough, and at "owner" side.
        if (!getStoreManager().storageVersionAtLeast(StorageVersion.WRITE_OWNED_CHILD_KEYS_TO_PARENTS)) {
          // don't write child keys to the parent if the storage version isn't high enough
          continue;
        }
        if (relationType == Relation.MANY_TO_ONE_BI) {
          // We don't store any "FK" of the parent at the child side (use parent key instead)
          continue;
        } else if (relationType == Relation.ONE_TO_ONE_BI && mmd.getMappedBy() != null) {
          // We don't store any "FK" at the non-owner side (use parent key instead)
          continue;
        }
      }

      Object value = relInfo.value;
      String propName = EntityUtils.getPropertyName(getStoreManager().getIdentifierFactory(), mmd);
      if (value == null) {
        // Nothing to extract
        checkSettingToNullValue(mmd, value);
        if (!datastoreEntity.hasProperty(propName) || datastoreEntity.getProperty(propName) != null) {
          modifiedEntity = true;
          EntityUtils.setEntityProperty(datastoreEntity, mmd, propName, value);
        }
      } else if (Relation.isRelationSingleValued(relationType)) {
        if (ec.getApiAdapter().isDeleted(value)) {
          value = null;
        } else {
          Key key = EntityUtils.extractChildKey(value, ec, owned ? datastoreEntity : null);
          if (key == null) {
            Object childPC = processPersistable(mmd, value);
            if (childPC != value) {
              // Child object has been persisted/attached, so update it in the owner
              op.replaceField(mmd.getAbsoluteFieldNumber(), childPC);
            }
            key = EntityUtils.extractChildKey(childPC, ec, owned ? datastoreEntity : null);
          }
          if (owned) {
            // Check that we aren't assigning an owned child with different parent
            if (!datastoreEntity.getKey().equals(key.getParent())) {
              throw new NucleusFatalUserException(GAE_LOCALISER.msg("AppEngine.OwnedChildCannotChangeParent",
                  key, datastoreEntity.getKey()));
            }
          }
          value = key;
          if (!datastoreEntity.hasProperty(propName) || !value.equals(datastoreEntity.getProperty(propName))) {
            modifiedEntity = true;
            EntityUtils.setEntityProperty(datastoreEntity, mmd, propName, value);
          }
        }
      } else if (Relation.isRelationMultiValued(relationType)) {
        if (mmd.hasCollection()) {
          Collection coll = (Collection) value;
          List<Key> keys = Utils.newArrayList();
          for (Object obj : coll) {
            // TODO Should process SCO before we get here so we have no deleted objects
            if (!ec.getApiAdapter().isDeleted(obj)) {
              Key key = EntityUtils.extractChildKey(obj, ec, owned ? datastoreEntity : null);
              if (key != null) {
                keys.add(key);
              } else {
                Object childPC = processPersistable(mmd, obj);
                key = EntityUtils.extractChildKey(childPC, ec, owned ? datastoreEntity : null);
                keys.add(key);
              }

              if (owned) {
                // Check that we aren't assigning an owned child with different parent
                if (!datastoreEntity.getKey().equals(key.getParent())) {
                  throw new NucleusFatalUserException(GAE_LOCALISER.msg("AppEngine.OwnedChildCannotChangeParent",
                      key, datastoreEntity.getKey()));
                }
              }
            }
          }
          value = keys;
          if (!datastoreEntity.hasProperty(propName) || !value.equals(datastoreEntity.getProperty(propName))) {
            modifiedEntity = true;
            EntityUtils.setEntityProperty(datastoreEntity, mmd, propName, value);
          }
        }
        // TODO Cater for PC array, maps
      }
    }

    relationStoreInfos.clear();

    return modifiedEntity;
  }

  // Nonsense about registering parent key
  private String getExpectedChildType(AbstractMemberMetaData dependent) {
    if (dependent.getCollection() != null) {
      CollectionMetaData cmd = dependent.getCollection();
      return cmd.getElementType();
    } else if (dependent.getArray() != null) {
      ArrayMetaData amd = dependent.getArray();
      return amd.getElementType();
    }
    return dependent.getTypeName();
  }

  // Nonsense about registering parent key
  private void addToParentKeyMap(KeyRegistry keyRegistry, Object childValue, Key key, ExecutionContext ec,
      String expectedType, boolean checkForPolymorphism) {
    if (checkForPolymorphism && childValue != null && !childValue.getClass().getName().equals(expectedType)) {
      AbstractClassMetaData acmd = ec.getMetaDataManager().getMetaDataForClass(childValue.getClass(),
          ec.getClassLoaderResolver());
      if (!MetaDataUtils.isNewOrSuperclassTableInheritanceStrategy(acmd)) {
        // TODO cache the result of this evaluation to improve performance
        throw new UnsupportedOperationException(
            "Received a child of type " + childValue.getClass().getName() + " for a field of type " +
            expectedType + ". Unfortunately polymorphism in relationships is only supported for the " +
            "superclass-table inheritance mapping strategy.");
      }
    }

    keyRegistry.registerParentKeyForOwnedObject(childValue, key);
  }

  private class RelationStoreInformation {
    AbstractMemberMetaData mmd;
    Object value;

    public RelationStoreInformation(AbstractMemberMetaData mmd, Object val) {
      this.mmd = mmd;
      this.value = val;
    }
  }

  /**
   * Method to make sure that the Entity has its parentKey assigned.
   * Returns the assigned parent PK (when we have a "gae.parent-pk" field/property in this class).
   * @return The parent key if the pojo class has a parent property. Note that a return value of {@code null} 
   *   does not mean that an entity group was not established, it just means the pojo doesn't have a distinct
   *   field for the parent.
   */
  Object establishEntityGroup() {
    Key parentKey = datastoreEntity.getParent();
    if (parentKey == null) {
      KeyRegistry keyReg = KeyRegistry.getKeyRegistry(op.getExecutionContext());
      if (keyReg.isUnowned(op.getObject())) {
        return null;
      }

      parentKey = EntityUtils.getParentKey(datastoreEntity, op);
      if (parentKey != null) {
        datastoreEntity = EntityUtils.recreateEntityWithParent(parentKey, datastoreEntity);
      }
    }

    AbstractMemberMetaData parentPkMmd = ((DatastoreManager)getStoreManager()).getMetaDataForParentPK(getClassMetaData());
    if (parentKey != null && parentPkMmd != null) {
      return parentPkMmd.getType().equals(Key.class) ? parentKey : KeyFactory.keyToString(parentKey);
    }
    return null;
  }
}