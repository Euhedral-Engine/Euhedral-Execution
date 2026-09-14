#include "olb3D.h"
#include "olb3D.hh"
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <numbers>

using namespace olb;
using T = double;
using D = descriptors::D3Q19<descriptors::FORCE>;

/// The adapter owns input translation and sampling. All numerical updates, including
/// link walls, initialization equilibrium, and Guo forcing, are OpenLB operations.
int main(int argc, char** argv) {
  try {
    if (argc != 3) throw std::runtime_error("usage: cfd-openlb input.txt output-directory");
    std::ifstream in(argv[1]);
    in.exceptions(std::ios::failbit | std::ios::badbit);
    std::filesystem::path output(argv[2]);
    std::filesystem::create_directories(output);
    int version, nx, ny, nz, steps, samples;
    T tau, rho0, dx, dt, densityScale, u0[3], force[3], amplitude;
    int modeY, modeZ, faces[6];
    in >> version >> nx >> ny >> nz >> steps >> samples;
    if (version != 1 || nx < 3 || ny < 3 || nz < 3 || steps < 1 || samples < 2)
      throw std::runtime_error("invalid exchange header");
    in >> tau >> rho0 >> dx >> dt >> densityScale;
    for (auto& u : u0) in >> u;
    for (auto& a : force) in >> a;
    in >> amplitude >> modeY >> modeZ;
    for (auto& f : faces) in >> f;
    std::vector<int> times(samples), mask(nx*ny*nz);
    for (auto& t : times) in >> t;
    for (auto& m : mask) in >> m;
    const auto index = [=](int x, int y, int z) { return x + nx*(y+ny*z); };
    const auto material = [&](int x, int y, int z) {
      int p[3] = {x,y,z}, n[3] = {nx,ny,nz};
      for (int a=0; a<3; ++a) {
        if (p[a] < 0 || p[a] >= n[a]) {
          if (faces[2*a] != 0) return -1;
          p[a] = (p[a]+n[a])%n[a];
        }
      }
      return mask[index(p[0],p[1],p[2])];
    };
    int inletFace;
    T inletVelocity[3], outletDensity, ramp;
    in >> inletFace >> inletVelocity[0] >> inletVelocity[1] >> inletVelocity[2] >> outletDensity >> ramp;
    int nb, ns, nc, nm;
    in >> nb >> ns >> nc >> nm;
    struct Shape {
      int id;
      std::unique_ptr<IndicatorF3D<T>> indicator;
      T scale[3]={1,1,1}, rotation[3]={0,0,0}, translation[3]={0,0,0};
    };
    std::vector<Shape> shapes;
    for (int kind=0; kind<4; ++kind) {
      int count = kind==0 ? nb : kind==1 ? ns : kind==2 ? nc : nm;
      for(int i=0;i<count;++i) {
        Shape shape; in >> shape.id;
        if(kind<3) {
          Vector<T,3> a,b; T r;
          for(int axis=0;axis<3;++axis) in >> a[axis];
          if(kind!=1) for(int axis=0;axis<3;++axis) in >> b[axis];
          if(kind!=0) in >> r;
          if(kind==0) shape.indicator=std::make_unique<IndicatorCuboid3D<T>>(b-a,a);
          if(kind==1) shape.indicator=std::make_unique<IndicatorSphere3D<T>>(a,r);
          if(kind==2) shape.indicator=std::make_unique<IndicatorCylinder3D<T>>(a,b,r);
        } else {
          std::string file; T unit;
          in >> std::quoted(file) >> unit;
          for(auto& a:shape.scale) {in >> a; a*=unit;}
          for(auto& a:shape.rotation) {in >> a; a*=std::numbers::pi/180;}
          for(auto& a:shape.translation) in >> a;
          shape.indicator=std::make_unique<STLreader<T>>(file,dx / *std::max_element(shape.scale,shape.scale+3),1.);
        }
        shapes.push_back(std::move(shape));
      }
    }

    /// Explicit extents avoid indicator rounding adding a periodic endpoint.
    initialize(&argc, &argv);
    CuboidDecomposition<T,3> cuboids({0.5,0.5,0.5}, 1., {nx,ny,nz}, 1);
    cuboids.setPeriodicity({faces[0]==0, faces[2]==0, faces[4]==0});
    HeuristicLoadBalancer<T> load(cuboids);
    SuperGeometry<T,3> geometry(cuboids, load, 2);
    UnitConverter<T,D> converter(dx,dt,dx*nx,dx/dt*0.01,(tau-.5)/3*dx*dx/dt,densityScale);
    SuperLattice<T,D> lattice(converter, cuboids, load, 2);
    auto& block = lattice.getBlock(0);
    auto& geo = geometry.getBlockGeometry(0);
    std::vector<int> ids;
    for (auto id : mask) if (id > 0 && std::find(ids.begin(),ids.end(),id)==ids.end()) ids.push_back(id);
    std::sort(ids.begin(),ids.end());
    for (int z=-2; z<nz+2; ++z) for (int y=-2; y<ny+2; ++y) for (int x=-2; x<nx+2; ++x) {
      int id = material(x,y,z);
      geo.set({x,y,z}, id == 0 ? 1 : id < 0 ? 2 : 3 + int(std::lower_bound(ids.begin(),ids.end(),id)-ids.begin()));
      if (id == 0) block.defineDynamics<ForcedBGKdynamics>({x,y,z});
      else block.defineDynamics<NoDynamics>({x,y,z});
      int position[3]={x,y,z}, extent[3]={nx,ny,nz};
      if(id==0 && inletFace>=0 && x>=0 && y>=0 && z>=0 && x<nx && y<ny && z<nz) {
        int axis=inletFace/2;
        if(position[axis]==0 || position[axis]==extent[axis]-1) {
          Vector<int,3> normal(0); normal[axis]=position[axis]==0 ? -1 : 1;
          int face=2*axis+(normal[axis]>0);
          if(face==inletFace) {
            boundary::ZouHeVelocity<T,D,BGKdynamics<T,D>> bc;
            block.defineDynamics({x,y,z},std::move(*bc.getDynamics(DiscreteNormalType::Flat,normal)));
          } else {
            boundary::ZouHePressure<T,D,BGKdynamics<T,D>> bc;
            block.defineDynamics({x,y,z},std::move(*bc.getDynamics(DiscreteNormalType::Flat,normal)));
          }
        }
      }
      auto cell = block.get(x,y,z);
      T u[3] = {u0[0] + amplitude*std::sin(2*std::numbers::pi*modeY*((y+ny)%ny)/ny)
                                 *std::cos(2*std::numbers::pi*modeZ*((z+nz)%nz)/nz), u0[1], u0[2]};
      cell.setField<descriptors::FORCE>(force);
      /// Native equilibrium is stored relative to descriptor weights in OpenLB.
      T eq[D::q];
      cell.getDynamics()->computeEquilibrium(cell,rho0,u,eq);
      for (int q=0;q<D::q;++q) cell[q]=eq[q];
      if (id == 0) lbm<D>::addExternalForce(cell,rho0,u,T{1},force);
      bool boundary = false;
      if (id == 0 && x>=0 && x<nx && y>=0 && y<ny && z>=0 && z<nz) {
        for (int q=1;q<D::q;++q) {
          auto c = descriptors::c<D>(q);
          int npos[3]={x+c[0],y+c[1],z+c[2]};
          bool outsideOpen = inletFace>=0 && (npos[inletFace/2]<0 || npos[inletFace/2]>=extent[inletFace/2]);
          if (!outsideOpen && material(npos[0],npos[1],npos[2]) != 0) {
            cell.setFieldComponent<descriptors::BOUZIDI_DISTANCE>(q, T{0.5});
            boundary = true;
          }
        }
        if (boundary) block.addPostProcessor(typeid(stage::PostStream),{x,y,z},meta::id<BouzidiPostProcessor>{});
      }
    }
    lattice.setParameter<descriptors::OMEGA>(1/tau);
    lattice.setStatisticsOff();
    lattice.initialize();
    /// initialize() executes setup postprocessors; restore the declared g(0) afterwards.
    /// Use OpenLB equilibrium and source initialization, never candidate populations.
    for(int z=-2;z<nz+2;++z) for(int y=-2;y<ny+2;++y) for(int x=-2;x<nx+2;++x) {
      if(material(x,y,z)!=0) continue;
      auto cell=block.get(x,y,z);
      T u[3]={u0[0]+amplitude*std::sin(2*std::numbers::pi*modeY*((y+ny)%ny)/ny)
                            *std::cos(2*std::numbers::pi*modeZ*((z+nz)%nz)/nz),u0[1],u0[2]};
      T eq[D::q];
      cell.getDynamics()->computeEquilibrium(cell,rho0,u,eq);
      for(int q=0;q<D::q;++q) cell[q]=eq[q];
      lbm<D>::addExternalForce(cell,rho0,u,T{1},force);
    }
    std::ofstream geometryOutput(output/"geometry.tsv");
    geometryOutput << "x\ty\tz\tmatchedId\tindependentId\n";
    for(int z=0;z<nz;++z) for(int y=0;y<ny;++y) for(int x=0;x<nx;++x) {
      int id=0;
      for(auto& shape:shapes) {
        T p[3]={(x+.5)*dx-shape.translation[0],(y+.5)*dx-shape.translation[1],(z+.5)*dx-shape.translation[2]};
        for(int axis=2;axis>=0;--axis) {
          int a=(axis+1)%3,b=(axis+2)%3; T c=std::cos(shape.rotation[axis]),s=std::sin(shape.rotation[axis]);
          T pa=c*p[a]+s*p[b],pb=-s*p[a]+c*p[b]; p[a]=pa; p[b]=pb;
        }
        for(int axis=0;axis<3;++axis) p[axis]/=shape.scale[axis];
        bool inside=false; (*shape.indicator)(&inside,p);
        if(inside && (id==0 || shape.id<id)) id=shape.id;
      }
      if(mask[index(x,y,z)]<0) id=mask[index(x,y,z)];
      geometryOutput << x << '\t' << y << '\t' << z << '\t' << mask[index(x,y,z)] << '\t' << id << '\n';
    }
    if(!geometryOutput) throw std::runtime_error("geometry export failed");
    geometryOutput.close();
    std::vector<std::unique_ptr<SuperLatticePhysBoundaryForce3D<T,D>>> forceFunctors;
    for (size_t i=0;i<ids.size();++i)
      forceFunctors.emplace_back(std::make_unique<SuperLatticePhysBoundaryForce3D<T,D>>(lattice,geometry,int(i)+3,converter));
    std::vector<T> forces(ids.size()*3,0);
    std::ofstream resolved(output/"method.json");
    resolved << std::setprecision(17)
      << "{\"stencil\":\"D3Q19\",\"collision\":\"BGK-second-order\",\"tau\":" << tau
      << ",\"densityReference\":" << rho0 << ",\"force\":\"Guo\",\"wall\":\"halfway-link\","
      << "\"population\":\"post-collision\",\"velocityCorrection\":\"minus-half-acceleration\","
      << "\"openBoundary\":\"" << (inletFace<0 ? "none" : "Zou-He") << "\","
      << "\"implementation\":\"OpenLB ForcedBGKdynamics, BouzidiPostProcessor q=0.5, native Zou-He on open faces\"}";
    resolved.close();
    if(!resolved) throw std::runtime_error("method export failed");
    std::filesystem::copy_file(argv[1],output/"case.txt");
    for (int step=0, sample=0; step<=steps; ++step) {
      if (step>0) {
        /// Start from g(t): communicate, stream and apply native walls, then collide.
        lattice.executePostProcessors(stage::PostCollide());
        lattice.AndStream();
        lattice.setProcessingContext(ProcessingContext::Evaluation);
        std::fill(forces.begin(),forces.end(),0);
        for (int z=0;z<nz;++z) for(int y=0;y<ny;++y) for(int x=0;x<nx;++x) {
          int id=mask[index(x,y,z)];
          if(id<=0) continue;
          size_t slot=std::lower_bound(ids.begin(),ids.end(),id)-ids.begin();
          T f[3]; int pos[4]={0,x,y,z};
          (*forceFunctors[slot])(f,pos);
          for(int a=0;a<3;++a) forces[3*slot+a]+=f[a];
        }
        lattice.setProcessingContext(ProcessingContext::Simulation);
        if(inletFace>=0) {
          int axis=inletFace/2, extent[3]={nx,ny,nz};
          for(int z=0;z<nz;++z) for(int y=0;y<ny;++y) for(int x=0;x<nx;++x) {
            int p[3]={x,y,z};
            if(mask[index(x,y,z)]!=0 || (p[axis]!=0 && p[axis]!=extent[axis]-1)) continue;
            auto cell=block.get(x,y,z);
            int face=2*axis+(p[axis]==extent[axis]-1);
            T u[3]={0,0,0};
            if(face==inletFace) {
              for(int a=0;a<3;++a) u[a]=inletVelocity[a]*(ramp==0 ? 1 : std::min(T{1},step/ramp));
              cell.defineU(u);
            } else { cell.defineRho(outletDensity); cell.defineU(u); }
          }
        }
        lattice.collide();
      }
      if (sample < samples && step==times[sample]) {
        lattice.setProcessingContext(ProcessingContext::Evaluation);
        std::ofstream fields(output/("snapshot-"+std::to_string(step)+".tsv"));
        fields << std::setprecision(17) << "x\ty\tz\ttime\tmaterial\tobstacle\tux\tuy\tuz\trho\tpressure\n";
        for(int z=0;z<nz;++z) for(int y=0;y<ny;++y) for(int x=0;x<nx;++x) {
          int id=mask[index(x,y,z)]; T rho=0,u[3]={0,0,0};
          if(id==0) {
            auto cell=block.get(x,y,z);
            lbm<D>::computeRhoU(cell,rho,u);
            for(int a=0;a<3;++a) u[a]=(u[a]-force[a]/2)*dx/dt;
          }
          fields << (x+.5)*dx << '\t' << (y+.5)*dx << '\t' << (z+.5)*dx << '\t' << step*dt << '\t'
            << (id==0 ? 0 : 1) << '\t' << id << '\t' << u[0] << '\t' << u[1] << '\t' << u[2] << '\t'
            << rho*densityScale << '\t' << (id==0 ? (rho-rho0)/3*densityScale*dx*dx/(dt*dt) : 0) << '\n';
        }
        fields.close();
        if(!fields) throw std::runtime_error("snapshot write failed");
        std::ofstream obs(output/("forces-"+std::to_string(step)+".tsv"));
        obs << std::setprecision(17) << "id\tfx\tfy\tfz\n";
        for(size_t i=0;i<ids.size();++i) obs << ids[i] << '\t' << forces[3*i] << '\t' << forces[3*i+1] << '\t' << forces[3*i+2] << '\n';
        obs.close();
        if(!obs) throw std::runtime_error("force export failed");
        lattice.setProcessingContext(ProcessingContext::Simulation);
        ++sample;
      }
    }
    std::ofstream completed(output/"completed");
    completed << "1\n";
    completed.close();
    if(!completed) throw std::runtime_error("completion export failed");
    return 0;
  } catch(const std::exception& e) {
    std::cerr << e.what() << '\n';
    return 1;
  }
}
