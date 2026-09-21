#pragma once
#include "math.hpp"
#include <cstdint>
#include <string>
#include <vector>

namespace tv {

struct Vertex {
    Vec3 position;
    Vec3 normal;
    Vec3 color;
};

struct MeshCpu {
    std::string name;
    std::vector<Vertex> vertices;
    std::vector<uint32_t> indices;
    Mat4 model = Mat4::identity();
    float materialType = 0.0f; // 0 lit, 1 emissive, 2 ocean, 3 stars, 4 sky
    float emission = 0.0f;
    bool visible = true;
};

class TitanicScene {
public:
    TitanicScene();
    void build();
    void update(float simSeconds);

    std::vector<MeshCpu>& meshes() { return meshes_; }
    const std::vector<MeshCpu>& meshes() const { return meshes_; }

    float simulationSeconds() const { return simSeconds_; }
    float historicalMinutes() const;
    bool finished() const { return simSeconds_ >= 300.0f; }
    void reset();

private:
    std::vector<MeshCpu> meshes_;
    float simSeconds_ = 0.0f;

    int ocean_=-1, stern_=-1, mid_=-1, bow_=-1;
    int sternWindows_=-1, midWindows_=-1, bowWindows_=-1;
    int fracture_=-1, stars_=-1, sky_=-1;

    void buildSky();
    void buildOcean();
    void buildShip();
    void buildStars();
};

} // namespace tv
