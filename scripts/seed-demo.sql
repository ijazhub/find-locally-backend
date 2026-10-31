-- Local demo data only. Run against findlocally after Flyway V1-V7.
-- Password for all @demo.findlocally.test accounts: DemoLocal@2026!
-- Deterministic IDs and DO NOTHING preserve demo edits and make reruns additive.
-- One DO statement is atomic. No cloud objects, emails or external queue deliveries are created.
DO $demo$
DECLARE
  names text[] := ARRAY['Demo Glow Studio','Demo Crumb Bakery','Demo Quick Delivery','Demo Feast Catering','Demo Stitch Studio'];
  owners text[] := ARRAY['Meera Shah','Kavya Rao','Arjun Das','Farah Khan','Dev Patel'];
  slugs text[] := ARRAY['makeup','home-baker','delivery','caterer','tailor'];
  areas text[] := ARRAY['Indiranagar','Koramangala','HSR Layout','Jayanagar','Whitefield'];
  offers text[][] := ARRAY[
    ['Party makeup','Bridal consultation','Everyday styling'],
    ['Chocolate celebration cake','Cupcake box','Sourdough loaf'],
    ['Local parcel delivery','Express document delivery','Scheduled pickup'],
    ['Family lunch catering','Birthday buffet','Office lunch package'],
    ['Blouse stitching','Trouser alteration','Dress fitting']
  ];
  modes text[]; bid uuid; oid uuid; cid uuid; sid uuid; bk uuid; conv uuid;
  cat uuid; addr uuid; start_time timestamptz; local_today date := (now() AT TIME ZONE 'Asia/Kolkata')::date;
  i int; j int; k int; booking_state text; money bigint; service_title text;
  hash text := '$2a$10$olDK8O1i768bGPqAbFdw..ljVnSDL9eBVshcrmk7/TCObVc19swr2';
BEGIN
  IF current_database() <> 'findlocally' THEN RAISE EXCEPTION 'This demo seed targets the local findlocally database'; END IF;
  IF (SELECT count(*) FROM categories WHERE slug = ANY(slugs)) <> 5 THEN
    RAISE EXCEPTION 'Apply category migration before seeding';
  END IF;

  FOR i IN 1..9 LOOP
    oid := md5('findlocally-demo-v1:user:' || i)::uuid;
    INSERT INTO users(id,name,email,password_hash)
    VALUES(oid,
      CASE WHEN i<=3 THEN (ARRAY['Asha Demo','Rohan Demo','Nisha Demo'])[i]
           WHEN i<=8 THEN owners[i-3] ELSE 'Priya Demo Onboarding' END,
      CASE WHEN i<=3 THEN (ARRAY['asha','rohan','nisha'])[i]
           WHEN i<=8 THEN slugs[i-3] ELSE 'onboarding' END || '@demo.findlocally.test', hash)
    ON CONFLICT(id) DO NOTHING;
    INSERT INTO user_roles(user_id,role) VALUES(oid,'CUSTOMER') ON CONFLICT DO NOTHING;
    IF i>3 THEN
      INSERT INTO user_roles(user_id,role) VALUES(oid,'BUSINESS') ON CONFLICT DO NOTHING;
    ELSE
      INSERT INTO addresses(id,user_id,label,line1,neighbourhood,city,state,pincode,lat,lng,is_default)
      VALUES(md5('findlocally-demo-v1:address:' || i)::uuid,oid,'Demo home',
        'Demo apartment ' || i || ', Sample Lane','Indiranagar','Bengaluru','Karnataka','560038',12.9784,77.6408,true)
      ON CONFLICT(id) DO NOTHING;
    END IF;
  END LOOP;

  FOR i IN 1..5 LOOP
    bid := md5('findlocally-demo-v1:business:' || i)::uuid;
    oid := md5('findlocally-demo-v1:user:' || (i+3))::uuid;
    SELECT id INTO STRICT cat FROM categories WHERE slug=slugs[i];
    modes := CASE WHEN i=1 OR i=5 THEN ARRAY['AT_STUDIO','AT_CLIENT']::text[]
                  WHEN i=2 THEN ARRAY['PICKUP','DELIVERY']::text[]
                  WHEN i=3 THEN ARRAY['DELIVERY']::text[] ELSE ARRAY['AT_CLIENT']::text[] END;
    INSERT INTO businesses(id,owner_user_id,status,current_step,completed_steps,category_id,
      business_name,owner_name,headline,bio,note,tags,city,neighbourhood,lat,lng,
      service_radius_km,work_mode,started_year,budget_min_amount,budget_max_amount,verification_status)
    VALUES(bid,oid,'ACTIVE','DONE',ARRAY['ABOUT','LOCATION','SERVICES','AVAILABILITY','WORK','VERIFICATION'],
      cat,names[i],owners[i],'Friendly local ' || slugs[i] || ' services',
      'Fictional demo business for exploring discovery, bookings and customer care.',
      'Demo profile. Please do not use for real orders.',ARRAY['demo','local','appointment'],
      'Bengaluru',areas[i],12.9784+i*0.002,77.6408+i*0.002,15,
      CASE WHEN i IN (3,4) THEN 'TRAVELS_TO_CLIENT' ELSE 'BOTH' END,
      2020+i%3,25000,500000,CASE WHEN i=5 THEN 'PENDING' ELSE 'VERIFIED' END)
    ON CONFLICT(id) DO NOTHING;

    FOR j IN 1..3 LOOP
      sid := md5('findlocally-demo-v1:service:' || i || ':' || j)::uuid;
      INSERT INTO services(id,business_id,name,description,duration_minutes,price_amount,fulfilment,sort_order)
      VALUES(sid,bid,offers[i][j],'Fictional demo offer; prices are in INR paise.',
        CASE WHEN j=2 THEN 60 ELSE 30 END,(i*250+j*150)*100,modes,j)
      ON CONFLICT(id) DO NOTHING;
    END LOOP;
    INSERT INTO business_hours(business_id,day,is_open,open_time,close_time)
      SELECT bid,d,true,'09:00'::time,'18:00'::time
      FROM unnest(ARRAY['MON','TUE','WED','THU','FRI','SAT','SUN']) d
      ON CONFLICT DO NOTHING;
    INSERT INTO business_blocked_dates VALUES(bid,local_today+14) ON CONFLICT DO NOTHING;
    INSERT INTO studio_notes(id,business_id,text)
      VALUES(md5('findlocally-demo-v1:note:'||i)::uuid,bid,'Demo: please message us about preferences before your appointment.')
      ON CONFLICT(id) DO NOTHING;
    IF i<5 THEN
      INSERT INTO business_badges(business_id,badge) VALUES(bid,'VERIFIED') ON CONFLICT DO NOTHING;
    END IF;

    -- Two completed bookings (one reviewable), pending, confirmed, cancelled, declined.
    FOR k IN 1..6 LOOP
      cid := md5('findlocally-demo-v1:user:' || CASE WHEN k<=4 THEN 1 WHEN k=5 THEN 2 ELSE 3 END)::uuid;
      addr := md5('findlocally-demo-v1:address:' || CASE WHEN k<=4 THEN 1 WHEN k=5 THEN 2 ELSE 3 END)::uuid;
      sid := md5('findlocally-demo-v1:service:' || i || ':1')::uuid;
      bk := md5('findlocally-demo-v1:booking:' || i || ':' || k)::uuid;
      booking_state := (ARRAY['COMPLETED','COMPLETED','AWAITING_CONFIRMATION','CONFIRMED','CANCELLED','DECLINED'])[k];
      start_time := ((local_today + CASE WHEN k<=2 THEN -k ELSE k-2 END) + time '10:00') AT TIME ZONE 'Asia/Kolkata';
      SELECT name,price_amount INTO service_title,money FROM services WHERE id=sid;
      INSERT INTO bookings(id,customer_id,business_id,service_id,status,starts_at,ends_at,fulfilment,
        address_id,address_snapshot,note,service_name,duration_minutes,price_amount,cancel_reason,decline_reason,created_at)
      SELECT bk,cid,bid,sid,booking_state,start_time,start_time+interval '30 minutes',modes[1],
        CASE WHEN modes[1] IN ('DELIVERY','AT_CLIENT') THEN addr END,
        CASE WHEN modes[1] IN ('DELIVERY','AT_CLIENT') THEN
          jsonb_build_object('label',a.label,'line1',a.line1,'city',a.city,'state',a.state,
            'pincode',a.pincode,'neighbourhood',a.neighbourhood,'lat',a.lat,'lng',a.lng) END,
        'Fictional demo booking',service_title,30,money,
        CASE WHEN k=5 THEN 'Demo customer changed plans' END,
        CASE WHEN k=6 THEN 'Demo provider unavailable' END,now()-interval '4 days'
      FROM addresses a WHERE a.id=addr
      ON CONFLICT(id) DO NOTHING;
      IF k=1 THEN
        INSERT INTO reviews(id,booking_id,business_id,author_id,rating,text,provider_reply,replied_at)
        VALUES(md5('findlocally-demo-v1:review:'||i)::uuid,bk,bid,cid,
          CASE WHEN i=3 THEN 4 ELSE 5 END,'Demo review: friendly service and clear communication.',
          CASE WHEN i%2=0 THEN 'Thank you for your demo feedback!' END,
          CASE WHEN i%2=0 THEN now() END)
        ON CONFLICT(id) DO NOTHING;
      END IF;
    END LOOP;

    cid := md5('findlocally-demo-v1:user:1')::uuid;
    INSERT INTO saved_businesses(user_id,business_id) VALUES(cid,bid) ON CONFLICT DO NOTHING;
    INSERT INTO follows(user_id,business_id) VALUES(cid,bid) ON CONFLICT DO NOTHING;
    conv := md5('findlocally-demo-v1:conversation:'||i)::uuid;
    INSERT INTO conversations(id,customer_id,business_id,booking_id)
    VALUES(conv,cid,bid,md5('findlocally-demo-v1:booking:'||i||':3')::uuid)
    ON CONFLICT(id) DO NOTHING;
    INSERT INTO messages(id,conversation_id,sender_id,text,sent_at,read_at)
    VALUES(md5('findlocally-demo-v1:message:'||i||':1')::uuid,conv,cid,
      'Hi! This is a demo enquiry. Can we discuss my preferences?',now()-interval '2 hours',now()-interval '1 hour'),
      (md5('findlocally-demo-v1:message:'||i||':2')::uuid,conv,oid,
      'Of course! Please share your preferences here.',now()-interval '1 hour',NULL)
    ON CONFLICT(id) DO NOTHING;
    INSERT INTO notifications(id,user_id,type,title,body,deep_link,read_at)
    VALUES(md5('findlocally-demo-v1:notification:'||i||':1')::uuid,cid,'MESSAGE',
      'New demo message from '||names[i],'Your demo provider replied.','/messages/'||conv,NULL),
      (md5('findlocally-demo-v1:notification:'||i||':2')::uuid,oid,'BOOKING_REQUESTED',
      'New demo booking request','Asha has requested your first service.',
      '/business/bookings/'||md5('findlocally-demo-v1:booking:'||i||':3')::uuid,NULL),
      (md5('findlocally-demo-v1:notification:'||i||':3')::uuid,cid,'BOOKING_CONFIRMED',
      'Demo booking confirmed','Your appointment is confirmed.',
      '/bookings/'||md5('findlocally-demo-v1:booking:'||i||':4')::uuid,now())
    ON CONFLICT(id) DO NOTHING;
  END LOOP;

  -- One ready-to-checkout item; choose a day clear of seeded live bookings and blocked dates.
  INSERT INTO cart_items(id,user_id,business_id,service_id,starts_at,fulfilment)
  VALUES(md5('findlocally-demo-v1:cart:1')::uuid,md5('findlocally-demo-v1:user:1')::uuid,
    md5('findlocally-demo-v1:business:2')::uuid,md5('findlocally-demo-v1:service:2:2')::uuid,
    ((local_today+3)+time '11:00') AT TIME ZONE 'Asia/Kolkata','DELIVERY')
  ON CONFLICT(id) DO NOTHING;

  INSERT INTO businesses(id,owner_user_id,status,current_step,completed_steps,category_id,business_name,owner_name,bio,onboarding_data)
  SELECT md5('findlocally-demo-v1:business:6')::uuid,md5('findlocally-demo-v1:user:9')::uuid,
    'DRAFT','LOCATION',ARRAY['ABOUT'],id,'Demo Priya Cakes','Priya Demo Onboarding',
    'Fictional business used to resume the onboarding wizard.',
    jsonb_build_object('about',jsonb_build_object('ownerName','Priya Demo Onboarding',
      'businessName','Demo Priya Cakes','category','home-baker',
      'bio','Fictional business used to resume the onboarding wizard.'))
  FROM categories WHERE slug='home-baker'
  ON CONFLICT(id) DO NOTHING;
END
$demo$;
