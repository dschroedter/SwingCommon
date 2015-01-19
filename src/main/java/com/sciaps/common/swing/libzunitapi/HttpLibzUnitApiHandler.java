package com.sciaps.common.swing.libzunitapi;

import com.sciaps.common.AtomicElement;
import com.sciaps.common.data.CalibrationShot;
import com.sciaps.common.data.IRCurve;
import com.sciaps.common.data.IRRatio;
import com.sciaps.common.data.Model;
import com.sciaps.common.data.Region;
import com.sciaps.common.data.Standard;
import com.sciaps.common.objtracker.DBObj;
import com.sciaps.common.objtracker.DBObj.ObjLoader;
import com.sciaps.common.spectrum.LIBZPixelSpectrum;
import com.sciaps.common.swing.global.LibzUnitManager;
import com.sciaps.common.swing.global.MutableObjectsManager;
import com.sciaps.common.swing.utils.StandardFinderUtils;
import com.sciaps.common.webserver.LIBZHttpClient;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author sgowen
 */
public final class HttpLibzUnitApiHandler implements LibzUnitApiHandler
{
    private static String getLibzUnitApiBaseUrl(String ipAddress)
    {
        final String urlBaseString = "http://" + ipAddress;

        return urlBaseString;
    }

    private LIBZHttpClient _libzHttpClient;

    @Override
    public boolean connectToLibzUnit()
    {
        String baseUrl = getLibzUnitApiBaseUrl(LibzUnitManager.getInstance().getIpAddress());

        _libzHttpClient = new LIBZHttpClient(baseUrl);

        LibzUnitManager.getInstance().setLibzUnitUniqueIdentifier("UNIQUE_LIBZ_UNIT_ID_HERE");

        return true;
    }

    @Override
    public boolean pullFromLibzUnit()
    {
        String baseUrl = getLibzUnitApiBaseUrl(LibzUnitManager.getInstance().getIpAddress());

        _libzHttpClient = new LIBZHttpClient(baseUrl);

        final Map<String, Standard> standards = getStandards();
        for(Map.Entry<String, Standard> entry : standards.entrySet()) {
            entry.getValue().mId = entry.getKey();
        }
        LibzUnitManager.getInstance().getStandardsManager().reset();
        LibzUnitManager.getInstance().getStandardsManager().getObjects().putAll(standards);

        Map<String, CalibrationShot> calibrationShots = getCalibrationShots();
        for (Map.Entry<String, CalibrationShot> entry : calibrationShots.entrySet())
        {
            entry.getValue().loadFields(new ObjLoader()
            {
                @Override
                public Object load(String id, Class<?> type)
                {
                    return standards.get(id);
                }
            });
        }
        LibzUnitManager.getInstance().getCalibrationShots().clear();
        LibzUnitManager.getInstance().getCalibrationShots().putAll(calibrationShots);

        final Map<String, Region> regions = getRegions();
        for(Map.Entry<String, Region> entry : regions.entrySet()){
            entry.getValue().mId = entry.getKey();
        }
        LibzUnitManager.getInstance().getRegionsManager().reset();
        LibzUnitManager.getInstance().getRegionsManager().getObjects().putAll(regions);

        Map<String, IRRatio> intensityRatios = getIntensityRatios();
        for (Map.Entry<String, IRRatio> entry : intensityRatios.entrySet())
        {
            entry.getValue().mId = entry.getKey();
            entry.getValue().loadFields(new ObjLoader()
            {
                @Override
                public Object load(String id, Class<?> type)
                {
                    return regions.get(id);
                }
            });
        }
        LibzUnitManager.getInstance().getIRRatiosManager().reset();
        LibzUnitManager.getInstance().getIRRatiosManager().getObjects().putAll(intensityRatios);

        Map<String, Model> calModels = getCalibrationModels();
        for (Map.Entry<String, Model> entry : calModels.entrySet())
        {
            entry.getValue().mId = entry.getKey();
            entry.getValue().loadFields(new ObjLoader()
            {
                @Override
                public Object load(String id, Class<?> type)
                {
                    return standards.get(id);
                }
            });

            for (Map.Entry<AtomicElement, IRCurve> irCurveEntry : entry.getValue().irs.entrySet())
            {
                irCurveEntry.getValue().loadFields(new ObjLoader()
                {
                    @Override
                    public Object load(String id, Class<?> type)
                    {
                        return regions.get(id);
                    }
                });
            }
        }
        LibzUnitManager.getInstance().getModelsManager().reset();
        LibzUnitManager.getInstance().getModelsManager().getObjects().putAll(calModels);

        return LibzUnitManager.getInstance().isValidAfterPull();
    }

    @Override
    public boolean pushToLibzUnit()
    {
        String baseUrl = getLibzUnitApiBaseUrl(LibzUnitManager.getInstance().getIpAddress());

        _libzHttpClient = new LIBZHttpClient(baseUrl);

        if (pushStandards())
        {
            if (pushRegions())
            {
                for (final Map.Entry<String, IRRatio> entry : LibzUnitManager.getInstance().getIRRatiosManager().getObjects().entrySet())
                {
                    entry.getValue().saveIds(new DBObj.IdLookup()
                    {
                        @Override
                        public String getId(Object obj)
                        {
                            for (Map.Entry<String, Region> regionEntry : LibzUnitManager.getInstance().getRegionsManager().getObjects().entrySet())
                            {
                                if (regionEntry.getValue() == obj)
                                {
                                    return regionEntry.getKey();
                                }
                            }

                            return null;
                        }
                    });
                }

                if (pushIntensityRatios())
                {
                    for (final Map.Entry<String, Model> modelEntry : LibzUnitManager.getInstance().getModelsManager().getObjects().entrySet())
                    {
                        for (final Map.Entry<AtomicElement, IRCurve> entry : modelEntry.getValue().irs.entrySet())
                        {
                            entry.getValue().saveIds(new DBObj.IdLookup()
                            {
                                @Override
                                public String getId(Object obj)
                                {
                                    for (Map.Entry<String, Region> regionEntry : LibzUnitManager.getInstance().getRegionsManager().getObjects().entrySet())
                                    {
                                        if (regionEntry.getValue() == obj)
                                        {
                                            return regionEntry.getKey();
                                        }
                                    }

                                    return null;
                                }
                            });
                        }

                        modelEntry.getValue().saveIds(new DBObj.IdLookup()
                        {
                            @Override
                            public String getId(Object obj)
                            {
                                return StandardFinderUtils.retreiveIdForStandard(obj);
                            }
                        });
                    }

                    if (pushCalibrationModels())
                    {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    @Override
    public LIBZPixelSpectrum getLIBZPixelSpectrum(final String shotId)
    {
        if (LibzUnitManager.getInstance().getLIBZPixelSpectra().containsKey(shotId))
        {
            return LibzUnitManager.getInstance().getLIBZPixelSpectra().get(shotId);
        }
        else
        {
            try
            {
                LIBZPixelSpectrum libzPixelSpectrum = _libzHttpClient.getCalibrationShot(shotId);
                LibzUnitManager.getInstance().getLIBZPixelSpectra().put(shotId, libzPixelSpectrum);

                return libzPixelSpectrum;
            }
            catch (IOException ex)
            {
                Logger.getLogger(HttpLibzUnitApiHandler.class.getName()).log(Level.SEVERE, null, ex);
            }

            return null;
        }
    }

    private Map<String, Standard> getStandards()
    {
        return getObjects(_libzHttpClient.mStandardsObjClient);
    }

    private Map<String, CalibrationShot> getCalibrationShots()
    {
        try
        {
            Map<String, CalibrationShot> calibrationShots = _libzHttpClient.getCalibrationShots();

            return calibrationShots;
        }
        catch (IOException ex)
        {
            Logger.getLogger(HttpLibzUnitApiHandler.class.getName()).log(Level.SEVERE, null, ex);
        }

        return null;
    }

    private Map<String, Region> getRegions()
    {
        return getObjects(_libzHttpClient.mRegionObjClient);
    }

    private Map<String, IRRatio> getIntensityRatios()
    {
        return getObjects(_libzHttpClient.mIRObjClient);
    }

    private Map<String, Model> getCalibrationModels()
    {
        return getObjects(_libzHttpClient.mModelObjClient);
    }

    private boolean pushStandards()
    {
        return push(_libzHttpClient.mStandardsObjClient, LibzUnitManager.getInstance().getStandardsManager());
    }

    private boolean pushRegions()
    {
        return push(_libzHttpClient.mRegionObjClient, LibzUnitManager.getInstance().getRegionsManager());
    }

    private boolean pushIntensityRatios()
    {
        return push(_libzHttpClient.mIRObjClient, LibzUnitManager.getInstance().getIRRatiosManager());
    }

    private boolean pushCalibrationModels()
    {
        return push(_libzHttpClient.mModelObjClient, LibzUnitManager.getInstance().getModelsManager());
    }

    private <T extends DBObj> Map<String, T> getObjects(LIBZHttpClient.BasicObjectClient<T> basicObjectClient)
    {
        Map<String, T> objects = new HashMap();

        try
        {
            List<String> objectIds = basicObjectClient.getIdList();
            if (objectIds != null && objectIds.size() > 0)
            {
                for (String objectId : objectIds)
                {
                    T object = basicObjectClient.getSingleObject(objectId);
                    object.mId = objectId;
                    objects.put(objectId, object);
                }
            }
        }
        catch (IOException ex)
        {
            Logger.getLogger(HttpLibzUnitApiHandler.class.getName()).log(Level.SEVERE, null, ex);
        }

        return objects;
    }

    private <T extends DBObj> boolean push(LIBZHttpClient.BasicObjectClient<T> basicObjectClient, MutableObjectsManager<T> mutableObjectsManager)
    {
        try
        {
            createObjects(basicObjectClient, mutableObjectsManager.getObjectsToCreate(), mutableObjectsManager.getObjects());
            updateObjects(basicObjectClient, mutableObjectsManager.getObjectsToUpdate(), mutableObjectsManager.getObjects());
            deleteObjects(basicObjectClient, mutableObjectsManager.getObjectsToDelete(), mutableObjectsManager.getObjects());

            return true;
        }
        catch (IOException ex)
        {
            Logger.getLogger(HttpLibzUnitApiHandler.class.getName()).log(Level.SEVERE, null, ex);

            return false;
        }
    }

    private <T extends DBObj> void createObjects(LIBZHttpClient.BasicObjectClient<T> basicObjectClient, Set<String> objectsToCreate, Map<String, T> workingLocalObjects) throws IOException
    {
        if (objectsToCreate.size() > 0)
        {
            Set<String> objectsCreated = new HashSet();

            try
            {
                for (String objectId : objectsToCreate)
                {
                    T object = workingLocalObjects.get(objectId);
                    String databaseObjectId = basicObjectClient.createObject(object);
                    object.mId = databaseObjectId;

                    objectsCreated.add(objectId);

                    workingLocalObjects.remove(objectId);
                    workingLocalObjects.put(databaseObjectId, object);
                }
            }
            finally
            {
                for (String objectId : objectsCreated)
                {
                    objectsToCreate.remove(objectId);
                }
            }
        }
    }

    private <T> void updateObjects(LIBZHttpClient.BasicObjectClient<T> basicObjectClient, Set<String> objectsToUpdate, Map<String, T> workingLocalObjects) throws IOException
    {
        if (objectsToUpdate.size() > 0)
        {
            Set<String> objectsUpdated = new HashSet();

            try
            {
                for (String objectId : objectsToUpdate)
                {
                    T object = workingLocalObjects.get(objectId);
                    basicObjectClient.updateObject(objectId, object);

                    objectsUpdated.add(objectId);
                }
            }
            finally
            {
                for (String objectId : objectsUpdated)
                {
                    objectsToUpdate.remove(objectId);
                }
            }
        }
    }

    private <T> void deleteObjects(LIBZHttpClient.BasicObjectClient<T> basicObjectClient, Set<String> objectsToDelete, Map<String, T> workingLocalObjects) throws IOException
    {
        if (objectsToDelete.size() > 0)
        {
            Set<String> objectsDeleted = new HashSet();

            try
            {
                for (String objectId : objectsToDelete)
                {
                    basicObjectClient.deleteObject(objectId);

                    objectsDeleted.add(objectId);
                }
            }
            finally
            {
                for (String objectId : objectsDeleted)
                {
                    objectsToDelete.remove(objectId);
                }
            }
        }
    }
}