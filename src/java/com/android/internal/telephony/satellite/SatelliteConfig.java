/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony.satellite;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.satellite.nano.SatelliteConfigData;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SatelliteConfig is utility class for satellite.
 * It is obtained through the getConfig() at the SatelliteConfigParser.
 */
public class SatelliteConfig {

    private static final String TAG = "SatelliteConfig";
    private static final String SATELLITE_DIR_NAME = "satellite";
    private static final String S2_CELL_FILE_NAME = "s2_cell_file";
    private int mVersion;
    private Map<Integer, Map<String, Set<Integer>>> mSupportedServicesPerCarrier;
    private List<String> mSatelliteRegionCountryCodes;
    private Boolean mIsSatelliteRegionAllowed;
    private Path mSatS2FilePath;
    private SatelliteConfigData.SatelliteConfigProto mConfigData;

    public SatelliteConfig(SatelliteConfigData.SatelliteConfigProto configData) {
        mConfigData = configData;
        mVersion = mConfigData.version;
        mSupportedServicesPerCarrier = getCarrierSupportedSatelliteServices();
        mSatelliteRegionCountryCodes = List.of(
                mConfigData.deviceSatelliteRegion.countryCodes);
        mIsSatelliteRegionAllowed = mConfigData.deviceSatelliteRegion.isAllowed;
        mSatS2FilePath = null;

        Log.d(TAG, "mVersion:" + mVersion + " | "
                + "mSupportedServicesPerCarrier:" + mSupportedServicesPerCarrier + " | "
                + "mSatelliteRegionCountryCodes:" + mSatelliteRegionCountryCodes + " | "
                + "mIsSatelliteRegionAllowed:" + mIsSatelliteRegionAllowed + " | "
                + "s2CellFile size:" + mConfigData.deviceSatelliteRegion.s2CellFile.length);
    }

    /**
     * @return a Map data with carrier_id, plmns and allowed_services.
     */
    private Map<Integer, Map<String, Set<Integer>>> getCarrierSupportedSatelliteServices() {
        SatelliteConfigData.CarrierSupportedSatelliteServicesProto[] satelliteServices =
                mConfigData.carrierSupportedSatelliteServices;
        Map<Integer, Map<String, Set<Integer>>> carrierToServicesMap = new HashMap<>();
        for (SatelliteConfigData.CarrierSupportedSatelliteServicesProto carrierProto :
                satelliteServices) {
            SatelliteConfigData.SatelliteProviderCapabilityProto[] satelliteCapabilities =
                    carrierProto.supportedSatelliteProviderCapabilities;
            Map<String, Set<Integer>> satelliteCapabilityMap = new HashMap<>();
            for (SatelliteConfigData.SatelliteProviderCapabilityProto capabilityProto :
                    satelliteCapabilities) {
                String carrierPlmn = capabilityProto.carrierPlmn;
                Set<Integer> allowedServices = new HashSet<>();
                for (int service : capabilityProto.allowedServices) {
                    allowedServices.add(service);
                }
                satelliteCapabilityMap.put(carrierPlmn, allowedServices);
            }
            carrierToServicesMap.put(carrierProto.carrierId, satelliteCapabilityMap);
        }
        return carrierToServicesMap;
    }

    /**
     * Get satellite plmns for carrier
     *
     * @param carrierId the carrier identifier.
     * @return Plmns corresponding to carrier identifier.
     */
    @NonNull
    public List<String> getAllSatellitePlmnsForCarrier(int carrierId) {
        if (mSupportedServicesPerCarrier != null) {
            Map<String, Set<Integer>> satelliteCapabilitiesMap = mSupportedServicesPerCarrier.get(
                    carrierId);
            if (satelliteCapabilitiesMap != null) {
                return new ArrayList<>(satelliteCapabilitiesMap.keySet());
            }
        }
        Log.d(TAG, "getAllSatellitePlmnsForCarrier : mConfigData is null or no config data");
        return new ArrayList<>();
    }

    /**
     * Get supported satellite services of all providers for a carrier.
     * The format of the return value - Key: PLMN, Value: Set of supported satellite services.
     *
     * @param carrierId the carrier identifier.
     * @return all supported satellite services for a carrier
     */
    @NonNull
    public Map<String, Set<Integer>> getSupportedSatelliteServices(int carrierId) {
        if (mSupportedServicesPerCarrier != null) {
            Map<String, Set<Integer>> satelliteCapaMap =
                    mSupportedServicesPerCarrier.get(carrierId);
            if (satelliteCapaMap != null) {
                return satelliteCapaMap;
            } else {
                Log.d(TAG, "No supported services found for carrier=" + carrierId);
            }
        } else {
            Log.d(TAG, "mSupportedServicesPerCarrier is null");
        }
        return new HashMap<>();
    }

    /**
     * Get carrier identifier set for the satellite
     *
     * @return carrier identifier set from the config data.
     */
    @NonNull
    public Set<Integer> getAllSatelliteCarrierIds() {
        if (mSupportedServicesPerCarrier != null) {
            return new ArraySet<>(mSupportedServicesPerCarrier.keySet());
        }
        return new ArraySet<>();
    }

    /**
     * @return satellite region country codes
     */
    @NonNull
    public List<String> getDeviceSatelliteCountryCodes() {
        if (mSatelliteRegionCountryCodes != null) {
            return mSatelliteRegionCountryCodes;
        }
        Log.d(TAG, "getDeviceSatelliteCountryCodes : mConfigData is null or no config data");
        return new ArrayList<>();
    }

    /**
     * @return satellite access allow value, if there is no config data then it returns null.
     */
    @Nullable
    public Boolean isSatelliteDataForAllowedRegion() {
        if (mIsSatelliteRegionAllowed == null) {
            Log.d(TAG, "getIsSatelliteRegionAllowed : mConfigData is null or no config data");
        }
        return mIsSatelliteRegionAllowed;
    }


    /**
     * @param context the Context
     * @return satellite s2_cell_file path
     */
    @Nullable
    public Path getSatelliteS2CellFile(@Nullable Context context) {
        if (context == null) {
            Log.d(TAG, "getSatelliteS2CellFile : context is null");
            return null;
        }

        if (isFileExist(mSatS2FilePath)) {
            Log.d(TAG, "File mSatS2FilePath is already exist");
            return mSatS2FilePath;
        }

        if (mConfigData != null && mConfigData.deviceSatelliteRegion != null) {
            mSatS2FilePath = copySatS2FileToPhoneDirectory(context,
                    mConfigData.deviceSatelliteRegion.s2CellFile);
            return mSatS2FilePath;
        }
        Log.d(TAG, "getSatelliteS2CellFile :"
                + "mConfigData is null or mConfigData.deviceSatelliteRegion is null");
        return null;
    }

    /**
     * @param context       the Context
     * @param byteArrayFile byte array type of protobuffer config data
     * @return the satellite_cell_file path
     */
    @Nullable
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public Path copySatS2FileToPhoneDirectory(@Nullable Context context,
            @Nullable byte[] byteArrayFile) {

        if (context == null || byteArrayFile == null) {
            Log.d(TAG, "copySatS2FileToPhoneDirectory : context or byteArrayFile are null");
            return null;
        }

        File satS2FileDir = context.getDir(SATELLITE_DIR_NAME, Context.MODE_PRIVATE);
        if (!satS2FileDir.exists()) {
            satS2FileDir.mkdirs();
        }

        Path targetSatS2FilePath = satS2FileDir.toPath().resolve(S2_CELL_FILE_NAME);
        try {
            InputStream inputStream = new ByteArrayInputStream(byteArrayFile);
            if (inputStream == null) {
                Log.d(TAG, "copySatS2FileToPhoneDirectory: Resource=" + S2_CELL_FILE_NAME
                        + " not found");
            } else {
                Files.copy(inputStream, targetSatS2FilePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            Log.e(TAG, "copySatS2FileToPhoneDirectory: ex=" + ex);
        }
        return targetSatS2FilePath;
    }

    /**
     * @return {@code true} if the SatS2File is already existed and {@code false} otherwise.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public boolean isFileExist(Path filePath) {
        if (filePath == null) {
            Log.d(TAG, "isFileExist : filePath is null");
            return false;
        }
        return Files.exists(filePath);
    }
}
