ALTER TABLE hotel_profile
    ADD COLUMN IF NOT EXISTS evidence_instance_configuration JSONB NOT NULL
        DEFAULT '{"facilityInstances":{},"assetInstances":{}}'::jsonb;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'hotel_profile'::regclass
          AND conname = 'ck_hotel_profile_evidence_instance_configuration'
    ) THEN
        ALTER TABLE hotel_profile
            ADD CONSTRAINT ck_hotel_profile_evidence_instance_configuration
            CHECK (
                jsonb_typeof(evidence_instance_configuration) = 'object'
                AND jsonb_typeof(evidence_instance_configuration -> 'facilityInstances') = 'object'
                AND jsonb_typeof(evidence_instance_configuration -> 'assetInstances') = 'object'
            );
    END IF;
END $$;

-- Existing pilot hotels receive conservative, editable business-name defaults.
-- They are deliberately not fabricated serial numbers. Administrators must replace
-- or extend them with each hotel's real facility/equipment names after deployment.
UPDATE hotel_profile profile
SET evidence_instance_configuration = jsonb_build_object(
        'facilityInstances', jsonb_build_object(
            'serviceFacilityIds',
                jsonb_build_array('百宝箱', '水吧台', '行李房')
                || CASE
                       WHEN coalesce((profile.operational_feature_flags ->> 'gym')::boolean, false)
                       THEN jsonb_build_array('健身房')
                       ELSE '[]'::jsonb
                   END,
            'publicAreaFacilityIds',
                jsonb_build_array('大堂', '百宝箱', '水吧台', '行李房', '洗衣房')
                || CASE
                       WHEN coalesce((profile.operational_feature_flags ->> 'gym')::boolean, false)
                       THEN jsonb_build_array('健身房')
                       ELSE '[]'::jsonb
                   END,
            'eveningPublicAreaFacilityIds',
                jsonb_build_array('门头灯光', '前厅大堂', '洗衣房', '楼层过道及电梯间')
        ),
        'assetInstances', jsonb_build_object(
            'warmingEquipmentIds',
                CASE
                    WHEN coalesce(profile.breakfast_service_enabled, false)
                    THEN jsonb_build_array('保温设备')
                    ELSE '[]'::jsonb
                END,
            'equipmentAssetIds', jsonb_build_array('空气能机组')
        )
    ),
    updated_at = now()
WHERE evidence_instance_configuration = '{"facilityInstances":{},"assetInstances":{}}'::jsonb;

COMMENT ON COLUMN hotel_profile.evidence_instance_configuration IS
    'Authoritative store-managed facility/asset instance lists used by minimumPerConfiguredFacility/Asset evidence validation; employee payload is never trusted as configuration.';
